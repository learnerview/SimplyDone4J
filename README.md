# SimplyDone4J

[![CI](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml)
[![Release](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Embeddable, Redis-backed job scheduling engine for Spring Boot — priority queues, retries, rate limiting, lease-based execution and webhooks in one dependency.**

## Overview

SimplyDone4J is a Java library (a Spring Boot starter, not a standalone service) that adds
reliable background job processing to any Spring Boot application. You add one dependency,
point it at the Redis you likely already run, register a handler, and submit jobs.

The engine is fully auto-configured: every tunable has a working default, all beans are
`@ConditionalOnMissingBean` (override any component by declaring your own), and the entire
operational footprint is Redis. There is no database, no scheduler daemon and no separate
worker process.

- Group / artifact: `io.github.learnerview:simplydone4j-spring-boot-starter`
- Version: `2.1.0`
- Built and tested against Spring Boot `4.1.0` / Java `21`

## Features

- **Priority queues** — `HIGH` / `NORMAL` / `LOW` served by weighted deficit round-robin (no starvation).
- **Idempotent submission** — deduplicated by `producer + idempotencyKey`; a duplicate returns the stored job.
- **Rate limiting** — per-producer sliding window, Redis-backed, with an in-memory fallback behind a circuit breaker that fails open.
- **Exponential backoff retries** — configurable attempts, initial delay, multiplier, cap and jitter.
- **Lease-based execution with fencing** — claims carry a TTL and a monotonic fencing token; expired leases are reaped and stale writers are rejected.
- **Atomic admission & cancellation** — per-queue capacity is checked and written in one Lua script; cancellation is a compare-and-set against the stored status.
- **Dead-letter queue** — jobs that exhaust their retry budget move to `DLQ`; `DeadLetterService` offers explicit recovery.
- **Webhook callbacks** — best-effort HTTP POST on completion with its own retry/backoff.
- **Job events** — a Spring `ApplicationEvent` is published on every lifecycle transition; a misbehaving listener cannot affect the job's recorded outcome.
- **Observability** — optional Micrometer metrics and an Actuator health indicator.
- **Graceful shutdown** — in-flight handlers drain before the executor stops.
- **Opt-in execution logs** — per-attempt history in Redis, off by default.
- **Redis Sentinel / Cluster support** — bring your own topology via `simplydone4j.redis.*`.

## Architecture

At a high level: producers submit jobs through `JobSubmissionService`, which persists a job
hash and atomically admits an id into a per-priority Redis sorted set. A scheduled
`SchedulerEngine` polls those sorted sets with deficit round-robin, claims batches, and hands
jobs to a worker pool that enforces a lease and a timeout. Retries, lease recovery, dead-letter
promotion, metrics and health are all driven off the same Redis data model.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the Redis key model, the algorithms
(deficit WRR, backoff, Lua rate limiter, circuit breaker, WATCH/MULTI claim, lease fencing) and
the design rationale.

## Tech Stack

| Area | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot `4.1.0` (parent POM) |
| Data access | Spring Data Redis (Lettuce) |
| Serialization | Jackson 2 (`com.fasterxml.jackson`) — `jackson-databind` + `jackson-datatype-jsr310` |
| Validation | Jakarta Bean Validation (`spring-boot-starter-validation`) |
| Boilerplate | Lombok (compile-time only, `provided` scope — no trace downstream) |
| Observability | Spring Boot Actuator + Micrometer (both `optional`) |
| Build | Maven (`maven-enforcer-plugin` gates Java 21 / Maven 3.8+) |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers (test scope) |

## Prerequisites

- **Java 21+** and **Maven 3.8+** to build (both enforced by `maven-enforcer-plugin`).
- **Redis 6 or 7** at runtime. A local `docker run -p 6379:6379 redis:7-alpine` is enough.
- No Redis or Docker is required to *build*; only the real-Redis integration tests need one
  (they are skipped automatically when none is reachable). See [Testing](#testing).

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.learnerview</groupId>
    <artifactId>simplydone4j-spring-boot-starter</artifactId>
    <version>2.1.0</version>
</dependency>
```

### 2. Start Redis

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
# With authentication:
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --requirepass mypassword
```

### 3. Point the application at Redis (`application.yml`)

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379
# For Redis Sentinel / Cluster, use simplydone4j.redis.* instead — see docs/CONFIGURATION.md.
```

All `simplydone4j.*` properties have defaults, so no further configuration is required.

### 4. Register a handler and submit a job

```java
@SpringBootApplication
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }

    @Bean
    CommandLineRunner demo(HandlerRegistry registry, JobSubmissionService jobs) {
        return args -> {
            registry.register("email", ctx -> "email-sent-" + ctx.getJobId());

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("email");
            req.setIdempotencyKey("order-123");
            req.setPayload(Map.of("to", "user@example.com"));

            JobSubmissionResponse res = jobs.submit("my-app", req);
            System.out.println("Submitted " + res.getJobId() + " status=" + res.getStatus());
        };
    }
}
```

Scheduling is on by default. To run a submission-only node that delegates execution elsewhere:

```bash
java -jar my-app.jar --simplydone4j.scheduler.enabled=false
```

## Configuration

Every property below is optional; each has a working default. The full reference — including
Redis HA, retention, relaxed binding and environment-variable equivalents — is in
[`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

| Property | Purpose | Default | Required |
|---|---|---|---|
| `simplydone4j.key-prefix` | Namespace for every Redis key (queues, indexes, locks) | `simplydone4j` | No |
| `simplydone4j.ttl-days` | Days component of finished-job retention | `0` | No |
| `simplydone4j.ttl-hours` | Hours component of finished-job retention (effective TTL floored at 1h) | `1` | No |
| `simplydone4j.idempotency-ttl-hours` | How long an idempotency key is remembered (floored at 1h) | `1` | No |
| `simplydone4j.scheduler.enabled` | Gate the scheduler + maintenance beans | `true` | No |
| `simplydone4j.scheduler.polling-interval-ms` | Scheduler poll interval | `1000` | No |
| `simplydone4j.scheduler.batch-size` | Jobs claimed per poll (throughput lever) | `10` | No |
| `simplydone4j.scheduler.weights.high/normal/low` | Deficit round-robin weights | `70` / `20` / `10` | No |
| `simplydone4j.queue.max-depth` | **Per-priority-queue** admission cap (`QueueFullException` above it) | `10000` | No |
| `simplydone4j.rate-limit.requests-per-minute` | Max submissions per producer per window | `60` | No |
| `simplydone4j.rate-limit.window-seconds` | Rate-limit window | `60` | No |
| `simplydone4j.retry.max-attempts` | Attempt budget before `DLQ` | `3` | No |
| `simplydone4j.retry.initial-delay-seconds` | Delay before the first retry | `5` | No |
| `simplydone4j.retry.backoff-multiplier` | Exponential backoff multiplier | `2.0` | No |
| `simplydone4j.retry.max-delay-seconds` | Cap on a single backoff | `300` | No |
| `simplydone4j.retry.jitter-factor` | Fraction of delay randomised | `0.2` | No |
| `simplydone4j.worker.lease-timeout-seconds` | How long a claim is valid | `30` | No |
| `simplydone4j.worker.retry-promoter-interval-ms` | Retry-promotion frequency | `1000` | No |
| `simplydone4j.worker.lease-reaper-interval-ms` | Lease-recovery frequency | `5000` | No |
| `simplydone4j.executor.core-pool-size` / `max-pool-size` | Worker thread pool sizing | `4` / `8` | No |
| `simplydone4j.executor.queue-capacity` | Worker work-queue capacity | `100` | No |
| `simplydone4j.executor.default-timeout-seconds` | Default handler timeout | `30` | No |
| `simplydone4j.executor.await-termination-seconds` | Graceful-shutdown drain window | `30` | No |
| `simplydone4j.uniqueness.enabled` | Cluster-wide overlap guard for `uniqueKey` jobs | `false` | No |
| `simplydone4j.uniqueness.ttl-seconds` | Lock hold time (`0` = job timeout + 30s) | `0` | No |
| `simplydone4j.webhook.enabled` | Master webhook switch | `true` | No |
| `simplydone4j.metrics.enabled` | Publish Micrometer instruments | `true` | No |
| `simplydone4j.health.enabled` | Register the Actuator health indicator | `true` | No |
| `simplydone4j.health.max-queue-depth` | **Total** depth across all queues that reports `DOWN` | `100000` | No |
| `simplydone4j.health.dead-letter-threshold` | DLQ count that reports `DOWN` (`0` disables) | `1` | No |

## Usage

**Schedule for later, set priority and retries:**

```java
JobSubmissionRequest req = new JobSubmissionRequest();
req.setJobType("report");
req.setIdempotencyKey("report-" + LocalDate.now());
req.setPriority("HIGH");
req.setNextRunAt(Instant.now().plusSeconds(3600));
req.setTimeoutSeconds(120);
req.setMaxAttempts(5);
req.setCallbackUrl("https://example.com/webhooks/job-complete");

JobSubmissionResponse res = jobs.submit("reports-service", req);
```

**Idempotent resubmission returns the existing job:**

```java
jobs.submit("reports-service", req).getJobId()
    .equals(jobs.submit("reports-service", req).getJobId()); // true
```

**Cancel a queued job (only `QUEUED` jobs can be cancelled):**

```java
jobs.cancelJob(res.getJobId());
```

**Read statistics:**

```java
QueueStatsResponse stats = monitoringService.getStats();
// stats.getTotalQueued(), getTotalRunning(), getTotalSuccess(), getTotalDlq(), getSuccessRate()
```

More runnable patterns — handlers, `JobContext`, lifecycle, events, webhooks, execution logs —
are in [`docs/GUIDE.md`](docs/GUIDE.md).

## Testing

```bash
mvn clean test      # unit + integration tests
mvn clean verify    # adds the enforcer (Java 21 / Maven 3.8+) gate
```

The suite is mostly unit tests that drive the Redis client through Mockito mocks. A handful of
tests pin atomicity against a **real** Redis and are skipped automatically when none is
reachable at `localhost:16379` (override with `IT_REDIS_HOST` / `IT_REDIS_PORT`, or
`-Dit.redis.host` / `-Dit.redis.port`).

This repository's last full run produced **213 tests, 0 failures, 0 errors, 0 skipped**, with
6 real-Redis classes aborted (0 tests executed) because no Redis was available — i.e. 21
tests were **not** exercised. See [Groundedness & Verification](#groundedness--verification)
and [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## Deployment

SimplyDone4J is embedded in your application — there is no separate process to deploy. You
package your Spring Boot app as usual (`mvn package` → an executable jar; the library itself
is a ~175 KB jar) and deploy it with Redis reachable. Key operational points: graceful
shutdown drains in-flight handlers; health and metrics come from Actuator/Micrometer; and
redis Sentinel/Cluster is configured through `simplydone4j.redis.*`.

See [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) for provisioning and packaging,
[`docs/PRODUCTION.md`](docs/PRODUCTION.md) for runtime hardening and a debugging checklist, and
[`docs/PRODUCTION_READINESS.md`](docs/PRODUCTION_READINESS.md) for a scored, evidence-based
readiness assessment.

## Limitations & Known Issues

An honest account of current behavior and trade-offs.

1. **Redis is the only durable backend.** The `QueueRepository` / `JobRepository` are
   Redis-only; there is **no in-memory job/queue backend** and no embedded mode. The only
   non-Redis path is the in-memory **rate-limiter fallback** behind the circuit breaker. If
   Redis is down at startup the Redis-backed beans fail to start; if it goes down at runtime,
   scheduling and submission fail. Redis HA (Sentinel/Cluster) is strongly recommended in
   production.
2. **`queue.max-depth` is enforced per priority queue, not in total.** Admission is an atomic
   Lua check-and-write against the *target* queue. Three queues can therefore each hold up to
   `max-depth`, for a worst-case retained total of `3 × max-depth`; and a single full queue
   rejects new jobs of that priority even when the other queues have room. The separate
   `health.max-queue-depth` threshold *is* a total across all priorities.
3. **Atomicity holds against Redis, not in memory.** Per-queue capacity admission
   (`enqueueIfUnderCapacity`, Lua), the claim (`WATCH`/`MULTI`), the status compare-and-set
   cancel (`transitionStatusIfCurrent`) and the fenced terminal write are atomic **because
   Redis executes them as one unit**. The shipped implementations and the tests that prove
   those properties are Redis-specific.
4. **Single-node vs cluster caveats.** Every atomic script/transaction the engine runs is
   single-key by design (so it is safe on Redis Cluster, which rejects cross-slot Lua). The
   starter only builds a connection factory for Sentinel/Cluster; it does not manage cluster
   topology, resharding or failover itself.
5. **Orphan `QUEUED` jobs on a hard crash.** If the process dies between writing the job hash
   and enqueuing its queue entry, the job exists as `QUEUED` with no queue entry. The engine
   compensates *failures* (an exception or a full queue during enqueue deletes the job and
   releases the idempotency key), but a true crash in that window leaves a record nothing
   scans — no maintenance task sweeps `QUEUED`. The window is narrow.
6. **Consumed idempotency ticket on a crash.** If the process dies after winning the `SETNX`
   idempotency lock but before saving the job, that key stays consumed for the lock TTL
   (default 1h) and the caller receives no job id. Inherent to optimistic dedup without a
   distributed transaction.
7. **Timed-out handlers are not interrupted.** The watchdog records the timeout but the handler
   keeps running on its worker thread until it returns. It cannot corrupt the job's outcome
   (the fenced write and `ScheduledFuture.cancel` guarantee exactly one recorded result), but it
   does hold a worker thread.
8. **Progress reporting is not wired.** `JobContext.setProgress(...)` is a no-op and
   `getProgress()` always returns `0.0`; treat them as reserved API. Cancellation is
   cooperative: handlers must poll `isCancellationRequested()`.
9. **In-memory rate-limiter fallback can diverge across instances.** When the circuit is open,
   each instance limits locally, so a distributed deployment may admit slightly different
   traffic until Redis recovers.
10. **Lease-recovery and polling granularity.** Lease reaping runs every `lease-reaper-interval-ms`
    (default 5s) and scheduling every `polling-interval-ms` (default 1s, first poll 2s after
    start). A job whose lease expires between ticks can take up to one interval to be recovered.
11. **DLQ is status-only.** Dead-lettered jobs are not moved to a separate structure; they stay
    in the status index and require an explicit `DeadLetterService` call to recover. There is no
    automatic DLQ replay.
12. **No job chaining / dependencies.** There is no `dependsOn` or "run B if A succeeds" support.
13. **Multi-tenancy is key-prefix only.** Isolation between deployments sharing one Redis is by
    `key-prefix`; it is a naming convention, not a security boundary.
14. **Some properties are accepted but inert.** `simplydone4j.redis.enabled` is declared but not
    currently enforced (topology is gated on `simplydone4j.redis.sentinel-nodes`), and
    `simplydone4j.rate-limit.slow-call-duration-ms` is reserved for API compatibility.

## Groundedness & Verification

### Verification status

`mvn clean test` was run on this checkout (Windows, Java 21, Maven; no Redis available):

- **213 tests run, 0 failures, 0 errors, 0 skipped.**
- **6 real-Redis test classes executed 0 tests** (aborted) because no Redis was reachable at
  `localhost:16379`, leaving **21 `@Test` methods not exercised**:
  `RedisIdempotencyServiceRealRedisTest` (2), `RedisJobRepositoryWriteSemanticsRealRedisTest` (9),
  `RedisQueueRepositoryWriteSemanticsRealRedisTest` (2), `RedisRateLimiterRealRedisTest` (4),
  `RetryPromotionRaceRealRedisTest` (2), `SchedulerEngineBatchRecoveryRealRedisTest` (2).
  These are precisely the tests that prove atomic admission, claim, fencing and rate limiting
  against a live server.
- **Not verified in this run:** live-Redis atomicity and concurrency, Redis Sentinel/Cluster
  topology and failover, sustained load/perf behavior, and delivery against a real webhook
  endpoint. Build artifact measured: the starter jar is **175.3 KB**.

### Data & evidence provenance

At runtime, all engine state lives in Redis (job hashes, priority sorted sets, status indexes,
idempotency/uniqueness keys, fencing counter, rate-limit windows). In the test suite, the Redis
repositories are driven through **Mockito mocks** (`StringRedisTemplate`), the metrics tests use
a **real Micrometer `SimpleMeterRegistry`**, the stress tests use **in-JVM fake repositories**,
and only the explicitly named `*RealRedisTest` classes talk to a real server. No sample data is
fabricated: every default, metric name and behavioral claim in this documentation was read from
the source in `src/main`.

### AI-output grounding

**There is no AI, ML or LLM functionality in this project.** SimplyDone4J is deterministic job
scheduling infrastructure; it contains no model inference, no generative component and no
external AI service call. Any "AI" association is limited to conventional static documentation
tooling, not runtime behavior.

## Roadmap

Candidate directions only — nothing below is committed or scheduled, and none of it exists in
`2.1.0`:

- Automatic dead-letter replay.
- Job chaining / `dependsOn` dependencies.
- Wiring `JobContext` progress reporting, and interrupting timed-out handlers.
- An in-memory / embedded backend for development and degraded mode.
- Event-driven (sub-poll-interval) scheduling.
- Additional tenant isolation beyond `key-prefix`.

## Contributing

1. Branch from `main` using `feature/*`, `bugfix/*`, `hotfix/*` or `release/*`.
2. Implement with tests (unit tests mock `StringRedisTemplate`; real-Redis tests use the
   `*RealRedisTest` convention) and use Conventional Commits.
3. Update the relevant docs under `docs/`.
4. Open a PR; CI (`mvn -B clean verify`) must be green.

See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for the local workflow, project layout and
release process.

## Security

Please do **not** open a public issue for a vulnerability. See [`SECURITY.md`](SECURITY.md) for
supported versions, private reporting and how secrets such as the Redis password should be
handled. In short: supply credentials through environment variables or a secrets manager, never
commit them, and treat job payloads — which are stored as plaintext strings in Redis — as
sensitive.

## License

MIT. See [`LICENSE`](LICENSE) for the full text. The POM declares the same license
(`https://opensource.org/licenses/MIT`).
