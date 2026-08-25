# SimplyDone4J

[![CI](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml)
[![Release](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Embeddable, Redis-backed background job scheduling engine for Spring Boot 4.x.

**SimplyDone4J is a lightweight Java dependency (not a service) that brings reliable job scheduling, priority queues, automatic retries, rate limiting, lease-based execution, and webhook callbacks to any Spring Boot application — with zero infrastructure beyond Redis.**

### Quick Start

**1. Add the dependency:**

```xml
<dependency>
    <groupId>io.github.learnerview</groupId>
    <artifactId>simplydone4j-spring-boot-starter</artifactId>
    <version>2.0.0</version>
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
# Optional: Redis Sentinel/Cluster configuration
# simplydone4j:
#   redis:
#     sentinel:
#       master: mymaster
#       nodes:
#         - host1:26379
#         - host2:26379
```

**4. Define a handler and submit your first job:**

The `JobHandler` interface now returns a `String` (job result or identifier):

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

### Configuration Reference

All properties under `simplydone4j.*`:

| Property | Default | Description |
|---|---|---|
| `scheduler.enabled` | `true` | Enable scheduler + worker maintenance. Set `false` for submission-only nodes |
| `scheduler.polling-interval-ms` | `1000` | How often the scheduler polls queues |
| `scheduler.queue-prefix` | `simplydone4j:queue` | Redis key prefix for queues |
| `scheduler.weights.high` | `70` | Scheduling weight for HIGH priority |
| `scheduler.weights.normal` | `20` | Scheduling weight for NORMAL priority |
| `scheduler.weights.low` | `10` | Scheduling weight for LOW priority |
| `rate-limit.requests-per-minute` | `60` | Max submissions per producer per window |
| `rate-limit.window-seconds` | `60` | Rate limit sliding window in seconds |
| `rate-limit.circuit-breaker.failures` | `5` | Failures before circuit opens |
| `rate-limit.circuit-breaker.reset-seconds` | `30` | Seconds before half-open retry |
| `rate-limit.circuit-breaker.slow-call-ms` | `2000` | Duration threshold for slow-call detection |
| `retry.max-attempts` | `3` | Max attempts (including initial try) |
| `retry.initial-delay-seconds` | `5` | Delay before first retry |
| `retry.backoff-multiplier` | `2.0` | Exponential backoff multiplier |
| `worker.lease-timeout-seconds` | `30` | Max lease hold time per worker |
| `worker.retry-promoter-interval-ms` | `1000` | Frequency of retry promotion |
| `worker.lease-reaper-interval-ms` | `5000` | Frequency of lease recovery |
| `queue.max-depth` | `10000` | Max jobs across all priority queues |
| `executor.core-pool-size` | `4` | Worker thread pool core size |
| `executor.max-pool-size` | `8` | Worker thread pool max size |
| `executor.queue-capacity` | `100` | Thread pool work queue capacity |
| `executor.keep-alive-seconds` | `60` | Idle thread keep-alive |
| `executor.default-timeout-seconds` | `30` | Default handler execution timeout |
| `executor.await-termination-seconds` | `30` | Graceful shutdown wait |
| `key-prefix` | `simplydone4j` | Global Redis key prefix |
| `ttl-days` | `30` | TTL for finished job data |
| `idempotency-ttl-hours` | `1` | TTL for idempotency locks |
| `monitoring.enabled` | `true` | Enable MonitoringService |
| `redis.sentinel.master` | — | Redis Sentinel master name |
| `redis.sentinel.nodes` | — | Redis Sentinel nodes (host:port) |

**Custom example:**

```yaml
simplydone4j:
  scheduler:
    polling-interval-ms: 500
    weights:
      high: 80
      normal: 15
      low: 5
  retry:
    max-attempts: 5
    initial-delay-seconds: 2
    backoff-multiplier: 3.0
  executor:
    core-pool-size: 8
    max-pool-size: 16
    await-termination-seconds: 60
  ttl-days: 7
  idempotency-ttl-hours: 24
  rate-limit:
    requests-per-minute: 120
    window-seconds: 30
    circuit-breaker:
      failures: 5
      reset-seconds: 30
      slow-call-ms: 2000
```

### Defining Job Handlers

Handlers implement the `JobHandler` functional interface:

```java
@FunctionalInterface
public interface JobHandler {
    String handle(JobContext context) throws Exception;
}
```

`JobContext` exposes:

| Method | Description |
|---|---|
| `getJobId()` | Unique job identifier |
| `getJobType()` | Job type this handler was registered for |
| `getProducer()` | Producer that submitted the job |
| `getPayload()` | Raw JSON payload string |
| `getAttemptCount()` | Current attempt number (0-based) |
| `getMaxAttempts()` | Maximum attempts configured |
| `getTimeoutSeconds()` | Job execution timeout |
| `getDeadline()` | Absolute deadline for job execution |
| `isCancellationRequested()` | Check if cancellation was requested |
| `requestCancellation()` | Request job cancellation |
| `getProgress()` | Get progress (0.0–1.0) if a `ProgressCallback` is set |
| `setProgress(double percent, String message)` | Set progress if a `ProgressCallback` is set |

**Register handlers through `HandlerRegistry`:**

```java
@Component
public class MyHandlers {

    private final HandlerRegistry registry;
    private final SomeService someService;

    public MyHandlers(HandlerRegistry registry, SomeService someService) {
        this.registry = registry;
        this.someService = someService;
    }

    @PostConstruct
    void register() {
        registry.register("send-email", ctx -> {
            someService.sendEmail(ctx.getPayload());
            return "email-sent";
        });

        registry.register("generate-report", ctx -> {
            someService.generateReport(ctx.getJobId());
            return "report-generated";
        });
    }
}
```

### Submitting jobs

Inject `JobSubmissionService` and call `submit(producer, request)`.

**Minimal request:**

```java
JobSubmissionRequest req = new JobSubmissionRequest();
req.setJobType("send-email");
req.setIdempotencyKey("email-001");

JobSubmissionResponse res = submissionService.submit("my-app", req);
// res.getJobId() → UUID, res.getStatus() → "QUEUED"
```

**With payload:**

```java
req.setPayload(Map.of(
    "to", "user@example.com",
    "subject", "Welcome!",
    "template", "welcome-email"
));
```

**Scheduled execution:**

```java
req.setNextRunAt(Instant.now().plusSeconds(3600)); // run in 1 hour
```

**Custom priority:**

```java
req.setPriority("HIGH"); // "HIGH", "NORMAL" (default), or "LOW"
```

**Custom retry count:**

```java
req.setMaxAttempts(10);
```

**Callback URL:**

```java
req.setCallbackUrl("https://myapp.com/webhooks/job-complete");
```

**Timeout:**

```java
req.setTimeoutSeconds(30); // execution timeout
```

**Full request fields:**

| Field | Required | Type | Description |
|---|---|---|---|
| `jobType` | Yes | String | Matches a registered handler |
| `idempotencyKey` | Yes | String | Deduplication key per producer |
| `priority` | No | String | `HIGH`, `NORMAL` (default), `LOW` |
| `payload` | No | `Map<String, Object>` | Arbitrary data passed to the handler |
| `nextRunAt` | No | Instant | Schedule for future execution |
| `maxAttempts` | No | Integer | Per-job override |
| `timeoutSeconds` | No | Integer | Execution timeout |
| `callbackUrl` | No | String | Completion notification URL |

### Idempotency

Submitting the same `producer + idempotencyKey` returns the existing job instead of creating a duplicate:

```java
JobSubmissionRequest req = new JobSubmissionRequest();
req.setJobType("send-email");
req.setIdempotencyKey("order-456");

JobSubmissionResponse res1 = submissionService.submit("my-app", req);
JobSubmissionResponse res2 = submissionService.submit("my-app", req);
// res2.getJobId().equals(res1.getJobId()) → true
```

**Important:** If the application crashes after winning the idempotency `SETNX` lock but before saving the job, the idempotency key remains consumed for the lock's TTL duration (default 1 hour). The caller receives no job ID and must retry after the TTL expires. This is a fundamental trade-off of optimistic deduplication without distributed transactions. Consider setting `idempotency-ttl-hours` appropriately for your tolerance.

### Job Lifecycle

```
QUEUED → RUNNING ──→ SUCCESS
  │         │
  │         ├──→ RETRY_SCHEDULED → QUEUED (loop)
  │         │
  │         └──→ DLQ (max retries exceeded)
  │
  └──→ CANCELLED
```

| Status | Description |
|---|---|
| `QUEUED` | Waiting in a priority queue |
| `RUNNING` | Claimed by a worker, executing |
| `RETRY_SCHEDULED` | Failed, waiting for next retry |
| `SUCCESS` | Completed successfully |
| `CANCELLED` | Cancelled by user |
| `DLQ` | Dead-letter — exceeded max retries (terminal) |
| `FAILED` | Legacy enum value, not actively assigned |

### Priorities and Weighted Scheduling

Three priority levels with configurable weights:

| Priority | Default Weight | When to use |
|---|---|---|
| `HIGH` | 70 | Password resets, payments, time-sensitive |
| `NORMAL` | 20 | Email notifications, standard processing |
| `LOW` | 10 | Background maintenance, bulk operations |

The scheduler uses **deficit weighted round-robin**. Each priority accumulates deficit proportional to its weight. The priority with the highest deficit that has queued jobs wins the next poll. Higher-priority queues drain faster while lower-priority queues still make progress — no starvation.

### Retry Mechanism

When a handler throws:

1. The failure is recorded in the execution log
2. If `attempt + 1 < maxAttempts` (retries remain):
   - Delay = `initialDelaySeconds × (backoffMultiplier ^ attemptCount)`
   - Status set to `RETRY_SCHEDULED` with computed `nextRunAt`
   - The retry promoter moves due retries back to `QUEUED`
3. If `attempt + 1 >= maxAttempts`:
   - Job moves to `DLQ` with the error message

**Example with `maxAttempts=3`, `delay=5s`, `multiplier=2.0`:**

| Execution | Attempt | Delay before next | Outcome on failure |
|---|---|---|---|
| Initial | 0 | 5s | RETRY_SCHEDULED |
| 1st retry | 1 | 10s | RETRY_SCHEDULED |
| 2nd retry | 2 | — | DLQ |

`maxAttempts=3` means 1 initial try + 2 retries, then DLQ.

### Rate Limiting

Per-producer sliding window rate limiter (default: 60 requests per 60 seconds). Uses **Redis sorted sets** for accuracy across distributed instances, with an **in-memory fallback** and **circuit breaker** if Redis is unavailable.

```yaml
simplydone4j:
  rate-limit:
    requests-per-minute: 120
    window-seconds: 30
```

When exceeded, `RateLimitExceededException` is thrown with a `retryAfterSeconds` hint.

**Circuit breaker:** If rate limit failures exceed `circuit-breaker.failures` (default 5), the circuit opens and subsequent requests immediately receive a `RateLimitExceededException` for `circuit-breaker.reset-seconds` (default 30s). After the timeout, the circuit transitions to half-open and allows a probe request through. A slow handler duration exceeding `slow-call-ms` (default 2000ms) also triggers the circuit.

### Scheduling and Maintenance

Scheduling (`SchedulerEngine`) and maintenance services (`WorkerMaintenanceServiceImpl`) are enabled by default when Redis is configured. They provide:

- **Polling** — polls priority queues and claims ready jobs using deficit round-robin
- **Retry promotion** — moves due `RETRY_SCHEDULED` jobs back to `QUEUED`
- **Lease recovery** — detects expired leases on `RUNNING` jobs, triggers retry with token fencing

**To run a submission-only node (no scheduling):**

```bash
java -jar my-app.jar --simplydone4j.scheduler.enabled=false
```

### Job Events

SimplyDone4J publishes Spring `ApplicationEvent`s for every lifecycle transition:

```java
@Component
public class JobEventListener {

    @EventListener
    public void onJobEvent(JobPublishedEvent event) {
        switch (event.event()) {
            case JOB_CREATED -> log.info("Job created: {}", event.data().getJobId());
            case JOB_STARTED -> log.info("Job started: {}", event.data().getJobId());
            case JOB_COMPLETED -> log.info("Job completed in {}ms", event.data().getDurationMs());
            case JOB_FAILED -> log.warn("Job failed: {}", event.data().getResult());
            case JOB_RETRY -> log.info("Retry {}/{}", event.data().getAttempt(), event.data().getMaxAttempts());
            case JOB_CANCELLED -> log.info("Job cancelled: {}", event.data().getJobId());
        }
    }
}
```

`JobEventData` fields: `jobId`, `jobType`, `producer`, `status`, `priority`, `result`, `attempt`, `maxAttempts`, `durationMs`, `timestamp`.

### Monitoring

Inject `MonitoringService` for queue statistics and job counts:

```java
@Component
public class MyMonitor {

    private final MonitoringService monitoringService;

    public MyMonitor(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    public void printStats() {
        QueueStatsResponse stats = monitoringService.getStats();
        System.out.printf("Queued=%d Running=%d Success=%d DLQ=%d (rate=%.1f%%)%n",
                stats.getTotalQueued(), stats.getTotalRunning(),
                stats.getTotalSuccess(), stats.getTotalDlq(),
                stats.getSuccessRate());

        Map<String, Long> byStatus = monitoringService.getCountByStatus();
        Map<String, Long> byQueue = monitoringService.getQueueDepths();
    }
}
```

**Fetch individual job details:**

```java
JobResponse job = submissionService.getJob(jobId);
```

### Production Deployment

#### Graceful Shutdown

The thread pool uses `CallerRunsPolicy` and waits for in-flight jobs on shutdown:

```yaml
simplydone4j:
  executor:
    await-termination-seconds: 60
```

#### Redis HA

Use Redis Sentinel or Cluster. Configure via `application.yml`:

```yaml
simplydone4j:
  redis:
    sentinel:
      master: mymaster
      nodes:
        - host1:26379
        - host2:26379
```

#### Data Retention

Retention differs by data type:

| Data | Redis key | Retention |
|---|---|---|
| Job record (terminal: SUCCESS/DLQ/CANCELLED) | `simplydone4j:job:<id>` | TTL of `ttl-days` (default 30), set at terminal transition |
| Execution logs | `simplydone4j:log:<id>` | Fixed 7-day TTL, max 50 entries per job |
| Status/priority index entries for finished jobs | `simplydone4j:idx:*` | Removed immediately at terminal transition (no zombies left behind) |
| Idempotency locks | `simplydone4j:idempotency:*` | `idempotency-ttl-hours` (default 1) |
| Rate-limit windows | `simplydone4j:ratelimit:*` | 2× window-seconds |

Note: jobs stuck in a non-terminal state (`QUEUED`/`RUNNING`/`RETRY_SCHEDULED`) carry no TTL — they are retained until they reach a terminal state or are cancelled. The lease reaper and retry promoter exist precisely to drive stuck jobs toward termination.

```yaml
simplydone4j:
  ttl-days: 90
```

#### Multi-tenancy

Isolate environments sharing the same Redis with `key-prefix`:

```yaml
# production
simplydone4j:
  key-prefix: "prod"

# staging
simplydone4j:
  key-prefix: "staging"
```

#### Resource Sizing

| Component | Guidance |
|---|---|
| Thread pool | `corePoolSize` ≈ CPU cores × 2. `maxPoolSize` handles bursts. |
| Queue capacity | Keep below `maxDepth` to avoid `QueueFullException`. |
| Redis memory | ~1–5 KB per job hash + log list. At 100K jobs/day with 30-day retention, expect 3–15 GB. Finished jobs are purged from status indexes immediately, so index memory stays proportional to in-flight work only. |

#### Actuator Health

Add `spring-boot-starter-actuator` for Redis health checks. Create a custom indicator:

```java
@Component
public class JobSystemHealthIndicator implements HealthIndicator {

    private final MonitoringService monitoringService;

    public JobSystemHealthIndicator(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @Override
    public Health health() {
        QueueStatsResponse stats = monitoringService.getStats();
        if (stats.getTotalDlQ() > 100) {
            return Health.down()
                    .withDetail("dlqCount", stats.getTotalDlq()).build();
        }
        return Health.up()
                .withDetail("totalQueued", stats.getTotalQueued())
                .withDetail("successRate", stats.getSuccessRate())
                .build();
    }
}
```

#### Micrometer / Prometheus Metrics

Add `micrometer-core` and bind job-count gauges:

```java
@Component
public class JobMetricsBinder {

    public JobMetricsBinder(MeterRegistry meterRegistry, JobRepository jobRepository) {
        for (JobStatus status : JobStatus.values()) {
            meterRegistry.gauge("simplydone4j.jobs." + status.name().toLowerCase(),
                    jobRepository, repo -> repo.countByStatus(status));
        }
    }
}
```

### Debugging Checklist

1. `redis-cli ping` — check Redis connectivity
2. `redis-cli ZCARD simplydone4j:queue:high` — verify queues exist
3. Check logs for `spring.profiles.active=worker`
4. `redis-cli HGETALL simplydone4j:job:<jobId>` — inspect job data
5. Monitor thread pool active count
6. `redis-cli CLIENT LIST` — check connected clients
7. Verify `simplydone4j.key-prefix` if multiple environments share Redis

### Demo Application

A complete Spring Boot demo is at the `simplydone4j-demo` sibling directory. It demonstrates:

- 4 job handlers (quick-success, failing-task, long-running, callback-test)
- REST API: submit, query, cancel, view stats
- Auto-submission of ~15 jobs on startup
- Job lifecycle events logged to console
- Rate limiting and idempotency tests

**Build and run:**

```bash
# Build the library first
cd SimplyDone4J
mvn clean install -DskipTests

# Build and run the demo
cd ../simplydone4j-demo
mvn clean package
java -jar target/simplydone4j-demo-1.0.1.jar
```

Access `http://localhost:8080/api/jobs/stats` for queue statistics.

### Docker

```bash
# Redis with authentication
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --requirepassword mypassword

# With Spring configuration
spring:
  data:
    redis:
      url: redis://:mypassword@localhost:6379
```

**Docker Compose:**

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_DATA_REDIS_URL=redis://redis:6379
      - SIMPLYDONE4J_SCHEDULER_ENABLED=true
    depends_on:
      - redis
```

### Building from Source

```bash
git clone https://github.com/learnerview/simplydone4j.git
cd SimplyDone4J
mvn clean install -DskipTests   # library only
```

Java 21+, Spring Boot 4.x, Maven 3.8+.

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

### License

MIT



### GitHub Actions CI/CD

The project includes two GitHub Actions workflows:

**CI (`.github/workflows/ci.yml`)** — Runs on every push to `main`/`develop` and on pull requests to `main`:
- Builds with Maven
- Runs all **79 tests** with Testcontainers (real Redis)
- Enforces Java 21 / Maven 3.8+ version requirements

**Release (`.github/workflows/release.yml`)** — Runs on GitHub release creation:
- Builds and verifies with `mvn clean verify`
- Signs artifacts with GPG
- Publishes to Maven Central (requires `MAVEN_USERNAME`, `MAVEN_PASSWORD`, GPG key secrets)

### Actuator Endpoints

Add `spring.boot.admin.enabled=true` or configure your own actuator endpoints:

| Endpoint | Description |
|---|---|
| `actuator/health` | Basic health check (incl. custom `JobSystemHealthIndicator`) |
| `actuator/info` | Application info with build metadata |
| `actuator/metrics` | Custom metrics: `simplydone4j.jobs.queued`, `simplydone4j.jobs.running`, `simplydone4j.jobs.success`, `simplydone4j.jobs.dlq` |
| `actuator/prometheus` | Prometheus-formatted metrics (if micrometer-prometheus added) |

### Git

```bash
git clone https://github.com/learnerview/simplydone4j.git
cd SimplyDone4J
# Branch naming convention:
# - feature/* for new features
# - bugfix/* for bug fixes
# - hotfix/* for production fixes
# - release/* for release preparation
```