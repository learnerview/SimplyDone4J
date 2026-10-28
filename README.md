# SimplyDone4J

[![CI](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml)
[![Release](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Reliable background jobs for Spring Boot, backed by the Redis you already run.**

SimplyDone4J is a Spring Boot starter, not a service. Add one dependency, register a
handler, and submit jobs. There is no scheduler daemon, no worker process and no second
database: Redis is the queue, the job store, the idempotency store and the lock service.

- Group / artifact: `io.github.learnerview:simplydone4j-spring-boot-starter`
- Version: `3.0.0`
- Java 21, Spring Boot 4.1

## Features

- **Priority queues** — `HIGH` / `NORMAL` / `LOW` with weighted deficit round-robin, so
  low-priority work is not starved.
- **Idempotent submission** — deduplicated by `producer + idempotencyKey`.
- **At-least-once execution** — claims carry a lease and a fencing token; expired leases
  are reaped and stale writes are rejected.
- **Crash recovery** — a queue reconciler, lease reaper and retry promoter close the windows
  a process crash can open.
- **Exponential backoff with jitter** — bounded attempts, then a dead-letter state.
- **Dead-letter recovery** — requeue with a fresh attempt budget.
- **Cooperative cancellation and progress** — visible through `JobContext` and `JobView`.
- **Uniqueness** — an optional cluster-wide lock for jobs that must not overlap.
- **Admission control** — your `AdmissionPolicy` decides; `queue.max-depth` provides
  backpressure.
- **Observability** — optional Micrometer metrics and an Actuator health indicator.
- **Graceful shutdown** — in-flight handlers drain before the executor stops.

## Requirements

- Java 21+
- Spring Boot 4.1+
- Redis 6 or 7, standalone or Sentinel

Redis Cluster is not supported; see [Limitations](#limitations).

## Quick start

Add the starter:

```xml
<dependency>
    <groupId>io.github.learnerview</groupId>
    <artifactId>simplydone4j-spring-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>
```

Register handlers in a `HandlerRegistry` bean and submit through `JobClient`:

```java
@Configuration
class JobConfig {

    @Bean
    HandlerRegistry handlerRegistry() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("email", ctx -> "sent:" + ctx.getJobId());
        return registry;
    }
}

@Service
class WelcomeService {

    private final JobClient jobClient;

    WelcomeService(JobClient jobClient) {
        this.jobClient = jobClient;
    }

    JobHandle sendWelcome(String email) {
        return jobClient.submit("email",
                Map.of("to", email),
                opts -> opts.producer("my-app").idempotencyKey("welcome:" + email));
    }
}
```

A handler returns a result string on success and throws on failure. The return value is
stored on the job and available through `jobClient.get(handle.id())`.

Point Spring Data Redis at your instance with `spring.data.redis.*`. Every `simplydone4j.*`
property has a sensible production default.

## Documentation

| Guide | Contents |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Distributed systems invariants: monotonic fencing tokens, two-phase idempotency reservation, Lua atomic state transitions, and Deficit Round-Robin scheduling. |
| [Operations](docs/OPERATIONS.md) | Production signals: Actuator health indicators, Micrometer metrics, Redis keyspace topology, and failure runbooks. |

## How it works

A job is written to Redis and added to its priority queue in one atomic step. A scheduler
claims due jobs with a fencing token, transitions them to `RUNNING`, and hands them to a
worker. The outcome is written only if the worker still holds the lease. Failures are
retried with backoff, then parked in `DLQ`.

Execution is **at-least-once**. A worker can die after the side effect and before the
outcome is recorded, so handlers that call external systems must be idempotent. See
[Architecture](docs/ARCHITECTURE.md) for details on fencing tokens and lease reclamation.

## Limitations

- **Redis is the only backend.** If Redis is unavailable, submission and execution stop;
  use Sentinel for high availability.
- **At-least-once, not exactly-once.** Duplicate execution is possible by design.
- **Redis Cluster is unsupported.** The atomic write spans keys that do not share a hash
  slot. Standalone and Sentinel are supported.
- **Timed-out handlers are not interrupted.** The timeout is recorded, but the handler keeps
  its worker until it returns.
- **Cancellation and progress are cooperative.** A handler that never checks cannot be
  stopped.
- **Recovery is second-scale.** A crashed worker's job is recovered within the lease and
  reaper intervals.
- **`DLQ` is a status, not a separate store.** There is no automatic replay; requeue
  explicitly.
- **No job chaining, ordering or strict priority.** A handler can submit the next job
  itself; priorities are weights.
- **`key-prefix` is isolation, not security.** A tenant with Redis access can read every
  key.

## Verification & Testing

The test suite validates atomic state transitions, lease fencing, two-phase idempotency reservation, retry backoff, and concurrency boundaries:

```bash
# Run unit & architecture tests, static analysis (SpotBugs), and package verification
./mvnw clean verify

# Optional: run integration tests against a live Redis instance
# docker run -d --name redis -p 6379:6379 redis:7-alpine
./mvnw verify -Dit.redis.host=localhost -Dit.redis.port=6379
```

## Security & Operational Notes

- **Credentials:** Supply Redis passwords and connection URLs via Spring Boot properties (`spring.data.redis.*`) or environment variables, never hardcoded in source.
- **Payload Privacy:** Payloads are serialized JSON stored in Redis hashes. If payloads contain sensitive financial or PII data, use `retention.clear-payload-on-completion: true` to purge payloads immediately upon reaching a terminal state.

## License

MIT. See [LICENSE](LICENSE).
