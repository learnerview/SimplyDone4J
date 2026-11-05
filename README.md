# SimplyDone4J

[![CI](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml)
[![Release](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.learnerview/simplydone4j-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.learnerview/simplydone4j-spring-boot-starter)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Embeddable, Redis-backed background job scheduling for Spring Boot 4.1.x

SimplyDone4J is a Java library, not a standalone service. It adds job
submission, weighted priority queues, retries, idempotency, lease-based
execution, dead-letter recovery, and execution history to a Spring Boot
application that already uses Redis.

This repository contains the `3.0.0` source line, built and tested with Java 21
and Spring Boot `4.1.2`. For a published release, use the version listed on
[Maven Central](https://central.sonatype.com/artifact/io.github.learnerview/simplydone4j-spring-boot-starter).

---

## Requirements

- Java 21
- Spring Boot `4.1.x`; the starter is compiled and tested with `4.1.2`
- Redis 7 or newer, standalone, Sentinel, or Cluster with a hash-tagged key prefix
- Maven 3.9 or newer when building from source

No SQL database or separate worker process is required.

---

## Quick Start

**1. Get the artifact:**

To use this checkout, install `3.0.0` into the local Maven repository:

```bash
mvn -B install -DskipTests
```

To use a published release instead, no local install is needed; take the version
from [Maven Central](https://central.sonatype.com/artifact/io.github.learnerview/simplydone4j-spring-boot-starter).

**2. Add the dependency:**

```xml
<dependency>
    <groupId>io.github.learnerview</groupId>
    <artifactId>simplydone4j-spring-boot-starter</artifactId>
    <version>3.0.0</version> <!-- or the latest published version from Maven Central -->
</dependency>
```

**3. Start Redis:**

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
# Or with authentication:
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --requirepass mypassword
```

**4. Configure `application.yml`:**

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379
```

**4. Define a handler and submit your first job:**

```java
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }

    @Bean
    JobHandler emailHandler() {
        return context -> "email-sent-" + context.getJobId();
    }

    @Bean
    CommandLineRunner submit(HandlerRegistry registry,
                             JobSubmissionService jobs,
                             JobHandler emailHandler) {
        return args -> {
            registry.register("email", emailHandler);

            JobSubmissionRequest request = new JobSubmissionRequest();
            request.setJobType("email");
            request.setIdempotencyKey("order-123");
            request.setPayload(Map.of("to", "user@example.com"));

            JobSubmissionResponse response = jobs.submit("my-app", request);
            System.out.println("Submitted " + response.getJobId());
        };
    }
}
```

The handler result is stored with the job. Throw an exception from a handler to
trigger the configured retry policy.

**5. Scheduling is enabled by default.** To run a submission-only node:

```bash
java -jar my-app.jar --simplydone4j.scheduler.enabled=false
```

Redis stores queue, job, lease, retry, and idempotency state. If Redis is
unavailable, submissions fail and `EngineStatusService` reports the engine as
unhealthy; there is no in-memory fallback.

---

## Run the demo from source

The demo is a separate Spring Boot application called **ParcelFlow**. It
accepts orders, runs fulfillment asynchronously, and exposes retries, delayed
work, cancellation, priority lanes, worker capacity, job inspection, workload
generation, and dead-letter recovery. From a clean checkout, install the starter
locally first so the demo can resolve `3.0.0`:

```bash
mvn -B install -DskipTests
docker run -d --name simplydone4j-demo-redis -p 6379:6379 redis:7-alpine
mvn -f demo/pom.xml spring-boot:run
```

Open `http://localhost:8080`. To use another Redis instance, set
`REDIS_HOST` and `REDIS_PORT`, for example `REDIS_PORT=6380`.

The UI includes:

- **Overview** — queue lanes, worker capacity, recent orders, and jobs.
- **Orders** — checkout, lifecycle timeline, cancellation, and progress.
- **Jobs** — payload, result, attempt history, and cancellation.
- **Dead letters** — exhausted jobs with replay or discard decisions.
- **Engine** — Redis, scheduler, maintenance, queue, and worker health.
- **Workload lab** — single jobs, batches, real orders, scenarios, and inventory.
- **Guide** — glossary of jobs, queues, workers, leases, retries, and DLQ.

Demo order and inventory records live in Redis under `parcelflow:*`; job, retry,
and dead-letter state lives under `simplydone4j:*`. Both survive application
restarts only when Redis persistence is enabled.

---

## Design scope

| Included | Not included |
|---|---|
| Embedded Spring Boot starter; no separate worker service | A standalone job server or scheduler daemon |
| Redis-backed queue, job, lease, retry, and idempotency state | A SQL or in-memory storage backend |
| Three priority levels with weighted deficit round-robin scheduling | Strict priority ordering |
| At-least-once execution with lease fencing | Exactly-once side effects |
| Per-attempt execution logs and lifecycle events | Cron expressions or job dependency graphs |
| Sentinel support and Cluster support with a hash-tagged prefix | Cross-slot Cluster operation |
| Explicit dead-letter list, replay, and discard | Automatic DLQ policy |

Handlers that change another system must be idempotent or use an outbox. See
[Guarantees](docs/GUARANTEES.md) for the precise execution contract.

### Implementation notes

| Problem | Approach | Evidence |
|---|---|---|
| Queue admission must not oversubscribe under concurrency | One Lua script checks the combined queue depth and performs `ZADD` atomically | [Architecture](docs/ARCHITECTURE.md#atomic-queue-admission) |
| Duplicate submissions must not create two jobs | Two-phase idempotency reservation (`pending:<jobId>` → `<jobId>`) with compare-and-set resolution | [Architecture](docs/ARCHITECTURE.md#submission-algorithm) |
| A superseded worker must not record an outcome | Lease tokens checked inside the same `WATCH/MULTI/EXEC` transaction as the terminal write | [Guarantees](docs/GUARANTEES.md) |
| Priority work must share the queue without starvation | Deficit weighted round-robin with batched claims | [Architecture](docs/ARCHITECTURE.md#scheduling-algorithm) |
| Crashes can leave a job queued but not enqueued | Orphan reconciliation checks membership and re-admits through the capacity path | [Architecture](docs/ARCHITECTURE.md#orphan-reconciliation) |
| Redis outages must not hang health checks | Bounded command timeouts for Sentinel/Cluster and pooled Lettuce connections | [Operations](docs/OPERATIONS.md#redis-failure-behavior) |

---

## Features

- **Priority queues** — HIGH, NORMAL, and LOW with weighted deficit round-robin scheduling
- **Idempotent submission** — deduplicates by `producer + idempotencyKey`
- **Exponential backoff retry** — configurable max attempts, initial delay, multiplier, and jitter
- **Lease-based execution** — expired leases are recovered with token fencing
- **Scheduled maintenance** — retry promotion, lease recovery, and orphan reconciliation
- **Job events** — Spring `ApplicationEvent` published on lifecycle transitions
- **Execution logs** — per-attempt history stored in Redis
- **Dead-letter queue** — jobs that exhaust retries move to `DLQ`
- **DLQ recovery** — list, count, replay, or discard through `DeadLetterService`
- **Engine status** — dependency-free `EngineStatusService` reports Redis reachability, loop state, queue depth, and worker capacity
- **Graceful shutdown** — in-flight jobs may finish within `executor.await-termination-seconds`
- **Auto-configuration** — components are registered from the starter dependency
- **Sentinel and Cluster support** — Cluster requires a hash-tagged `key-prefix`

---

## Documentation

| Guide | Description |
|---|---|
| [User Guide](docs/GUIDE.md) | Public API, handlers, submission, idempotency, lifecycle, retries, events, cancellation, execution logs |
| [Configuration Reference](docs/CONFIGURATION.md) | Complete property table, YAML examples, environment variables, Redis HA, retention, deployment checks |
| [Operations](docs/OPERATIONS.md) | Health probes, alerting, Redis failure behavior, capacity, DLQ runbook, troubleshooting |
| [Architecture](docs/ARCHITECTURE.md) | Redis data model and algorithms: two-phase idempotency, atomic admission, deficit WRR, batched claims, lease renewal, retry promotion, reaping, retention |
| [Guarantees](docs/GUARANTEES.md) | Execution promises, enforcement mechanisms, proving tests, and deliberate non-guarantees |
| [Verification](docs/VERIFICATION.md) | Reproducible test, load, failover, health, and dependency-scan checks with observed results |
| [Releasing](docs/RELEASING.md) | Local build, release bundle, Maven Central workflow, and required secrets |
| [Security](SECURITY.md) | Supported versions, private vulnerability reporting, and deployment hardening |
| [Contributing](CONTRIBUTING.md) | Development setup, test commands, and pull-request expectations |
| [Changelog](CHANGELOG.md) | Release history |

---

## Minimal Configuration

```yaml
simplydone4j:
  retry:
    max-attempts: 3
    initial-delay-seconds: 5
    backoff-multiplier: 2.0
  scheduler:
    weights:
      high: 70
      normal: 20
      low: 10
  ttl-days: 0
  ttl-hours: 1
```

All properties → [Configuration Reference](docs/CONFIGURATION.md)

---

## Operational contract

SimplyDone4J provides at-least-once execution. Lease fencing allows only the
current lease holder to record an attempt outcome. Handlers must still be
idempotent because a process can stop after an external side effect and before
the outcome write.

1. **Submission window** — Job state and queue admission use separate Redis operations. A process crash between those operations can leave an orphan job record. Orphan reconciliation re-queues it automatically after `worker.orphan-grace-ms`; the duplicate resolver also waits for queue admission before reporting success. The idempotency reservation still expires according to `idempotency-ttl-hours`.

2. **Idempotency reservation** — A process crash after the idempotency reservation and before job persistence keeps the reservation until its TTL expires. This is the explicit trade-off of optimistic deduplication without a distributed transaction.

3. **Redis is a hard dependency** — Redis stores queue, job, lease, retry, and idempotency state. Redis availability is required for submissions and scheduling. Use Sentinel, managed Redis, or a tested failover plan when Redis availability matters.

4. **No embedded mode** — The library does not silently fall back to an in-memory queue.

5. **Lease recovery interval** — Lease recovery runs at a fixed interval. An expired lease can remain visible until the next recovery pass.

6. **Scheduler wake-up interval** — The scheduler uses a signal for prompt wake-up and a bounded polling interval as a loss-tolerant fallback.

7. **No multi-tenant isolation beyond key-prefix** — Sharing a Redis instance requires manual `key-prefix` configuration. There is no namespace isolation at the Redis data structure level.

8. **DLQ is status-indexed** — Jobs with `DLQ` status remain in the status index rather than a separate Redis queue. Recovery is explicit: `DeadLetterService.list/count` inspect them, `replay` resets attempts and re-queues, and `discard` deletes permanently. Nothing replays automatically, so an operator or application policy must decide.

9. **No job chaining** — The library cannot express "run job B only if job A succeeds." There is no `dependsOn` field or event-based triggering for downstream jobs.

10. **Queue depth is an admission cap** — Submissions and scheduled retry promotions are rejected or deferred at `queue.max-depth`. Claim restoration is an emergency safety path and can exceed the cap by at most the number of in-flight workers.

11. **Replay is at-least-once** — Replaying a dead-lettered job resets its attempt count and runs it again. If the original attempt already caused an external side effect, the handler must be idempotent or use an outbox. A queue-full replay rolls the record back to `DLQ`; a concurrent replay/discard loses the compare-and-set and receives an error.

12. **Status is pull-based** — `EngineStatusService.status()` performs a Redis `PING` and reads local loop state. Actuator and Micrometer are not required. Expose it from your own endpoint, log it on a timer, or alert on `healthy()`. On Sentinel/Cluster, `redis.command-timeout-ms` (default 5s) bounds the check during a Redis outage; for standalone Redis, set `spring.data.redis.timeout` so probes fail fast instead of waiting for a TCP timeout.

---

## License

MIT
