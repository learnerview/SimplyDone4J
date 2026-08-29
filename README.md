# SimplyDone4J

[![CI](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml)
[![Release](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Embeddable, Redis-backed background job scheduling engine for Spring Boot 4.x.

**SimplyDone4J is a lightweight Java dependency (not a service) that brings reliable job scheduling, priority queues, automatic retries, rate limiting, lease-based execution, and webhook callbacks to any Spring Boot application — with zero infrastructure beyond Redis.**

---

### Quick Start

**1. Add the dependency:**

```xml
<dependency>
    <groupId>io.github.learnerview</groupId>
    <artifactId>simplydone4j-spring-boot-starter</artifactId>
    <version>2.0.1</version>
</dependency>
```

**2. Start Redis:**

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
# Or with authentication:
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --requirepass mypassword
```

**3. Configure `application.yml`:**

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379
# Optional: Redis Sentinel/Cluster — see docs/CONFIGURATION.md
```

**4. Define a handler and submit your first job:**

The `JobHandler` interface returns a `String` (job result or identifier):

```java
@SpringBootApplication
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }

    @Bean
    public JobHandler emailHandler(JobContext context) {
        return ctx -> "email-sent-" + ctx.getJobId();
    }

    @Bean
    public CommandLineRunner demo(HandlerRegistry registry,
                                    JobSubmissionService submissionService) {
        return args -> {
            registry.register("email", emailHandler());

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("email");
            req.setIdempotencyKey("order-123");

            JobSubmissionResponse res = submissionService.submit("my-app", req);
            System.out.println("Submitted: " + res.getJobId() + " status=" + res.getStatus());
        };
    }
}
```

**5. Scheduling is enabled by default.** To disable it (submission-only node):

```bash
java -jar my-app.jar --simplydone4j.scheduler.enabled=false
```

That's it. Your jobs are queued in Redis, scheduled with weighted priority, retried on failure, and logged.

---

### Why SimplyDone4J?

| Feature | SimplyDone4J | Quartz | JobRunr |
|---|---|---|---|
| Runtime | JVM embedded (no service) | JVM embedded | JVM embedded |
| Backend | Redis only | SQL database | SQL / NoSQL |
| Spring Boot | Native starter 4.x | Requires adapter | Requires adapter |
| Priority queues | HIGH / NORMAL / LOW with weighted deficit scheduling | Limited | Supported |
| Rate limiting | Built-in with Redis + in-memory fallback | Not built-in | Not built-in |
| Setup | 1 dependency + Redis | DB schema + config | DB schema + config |
| Footprint | ~15 KB jar | ~1.5 MB | ~500 KB |

You don't need a database, a separate worker process, or external infrastructure — just Redis, which you likely already have for caching.

---

### Features

- **Priority queues** — HIGH, NORMAL, LOW with weighted deficit round-robin scheduling
- **Idempotent submission** — deduplicates by `producer + idempotencyKey`
- **Rate limiting** — per-producer sliding window (Redis-backed with in-memory fallback + circuit breaker)
- **Exponential backoff retry** — configurable max attempts, initial delay, multiplier
- **Lease-based execution** — jobs leased to workers; expired leases auto-recovered with token fencing
- **Scheduled maintenance** — retry promoter and lease reaper run at configurable intervals
- **Job events** — Spring `ApplicationEvent` published on every lifecycle transition
- **Execution logs** — per-attempt history stored in Redis
- **Dead-letter queue** — jobs exceeding max retries automatically moved to DLQ
- **Graceful shutdown** — in-flight jobs complete before the application stops
- **Health monitoring** — built-in metrics and status counts
- **Callback URLs** — HTTP callbacks on job completion
- **Fully auto-configured** — drop the dependency, configure Redis, go
- **Redis Sentinel/Cluster support** — high availability configuration
- **Micrometer/Prometheus metrics** — export job counts and status rates
- **Actuator health indicators** — custom job system health checks

---

### Documentation

| Guide | Description |
|---|---|
| [Configuration Reference](docs/CONFIGURATION.md) | Complete property table, YAML examples, env vars, Redis HA, retention |
| [User Guide](docs/GUIDE.md) | Handlers, submission, idempotency, lifecycle, retries, rate limiting, events, cancellation, progress, execution logs, monitoring |
| [Production Deployment](docs/PRODUCTION.md) | Resource sizing, health checks, metrics, Docker/Compose, debugging |
| [Architecture](docs/ARCHITECTURE.md) | Redis data model, algorithms (deficit WRR, backoff, Lua rate-limiter, circuit breaker, WATCH/MULTI claim, lease fencing), design decisions |
| [Development](docs/DEVELOPMENT.md) | Local build/test, demo app, CI/CD, release process, project layout |

---

### Minimal Configuration

```yaml
simplydone4j:
  retry:
    max-attempts: 3
    initial-delay-seconds: 5
    backoff-multiplier: 2.0
  rate-limit:
    requests-per-minute: 60
    window-seconds: 60
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

### Known Limitations

Honest admission of trade-offs — not blockers, but considerations:

1. **Orphan jobs on crash** — If the application crashes between saving the job hash to Redis and adding the job ID to the priority queue, the job exists in the hash store but not in any queue. These orphan jobs are self-healing: the idempotency lock expires after `idempotency-ttl-hours` (default 1 hour), allowing the caller to resubmit.

2. **Consumed idempotency ticket on crash** — If the application crashes after winning the idempotency `SETNX` lock but before saving the job, the idempotency key remains consumed for the lock's TTL duration. The caller receives no job ID and must retry after the TTL expires (default 1 hour). This is a fundamental trade-off of optimistic deduplication without distributed transactions.

3. **Redis is a hard dependency** — SimplyDone4J does not support an embedded mode or a local queue fallback. If Redis is unavailable at startup, all services that depend on `RedisTemplate` fail to start. If Redis becomes unavailable at runtime, scheduling stops, job submissions fail, and monitoring returns no data. **Redis HA (Sentinel or Cluster) is strongly recommended for production.**

4. **No embedded mode** — Requires Redis running; no local queue fallback for development or degraded mode.

5. **Rate limiter fallback inconsistency** — In distributed deployments, some application instances may have the Lua script loaded while others fall back to in-memory logic, resulting in slightly different rate limiting behavior across instances.

6. **Lease timeout granularity** — Lease timeouts are checked at fixed intervals (default 5s for lease reaper). Jobs with leases expiring between intervals may take up to one full interval to be recovered.

7. **Scheduler polling interval** — The scheduler polls queues at a fixed interval (default 1s). For very low-latency requirements, consider event-driven integration (future enhancement).

8. **No multi-tenant isolation beyond key-prefix** — Sharing a Redis instance requires manual `key-prefix` configuration. No namespace isolation at the Redis data structure level.

9. **DLQ is status-only** — Jobs with `DLQ` status are not moved to a separate Redis queue; they remain in the status index. No dedicated DLQ recovery API is provided (manual Redis intervention required).

10. **No job chaining** — Cannot express "run job B only if job A succeeds." No `dependsOn` field or event-based triggering for downstream jobs.

---

### License

MIT