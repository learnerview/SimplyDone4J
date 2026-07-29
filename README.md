# SimplyDone4J

[![CI](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml/badge.svg)](https://github.com/learnerview/simplydone4j/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Embeddable background job scheduling engine for Spring Boot. Redis-backed, priority-aware, lease-based execution with rate limiting, automatic retries with exponential backoff, and dead-letter queue management.

## Features

- **Priority queues** — HIGH, NORMAL, LOW with weighted deficit round-robin scheduling
- **Idempotent submission** — deduplicates jobs by `producer + idempotencyKey`
- **Rate limiting** — per-producer sliding window rate limiter (Redis-backed with in-memory fallback)
- **Exponential backoff retry** — configurable max attempts, initial delay, and multiplier
- **Lease-based execution** — jobs are leased to workers; expired leases auto-recover
- **Scheduled maintenance** — retry promoter + lease reaper run at configurable intervals
- **Job events** — Spring `ApplicationEvent` fired on create, start, complete, fail, retry, cancel
- **Execution logs** — per-job attempt history stored in Redis
- **Dead-letter queue** — jobs exceeding max retries are moved to DLQ status
- **Fully auto-configured** — drop in the dependency, configure Redis, and go

## Table of Contents

- [Add dependency](#add-dependency)
- [Quick start](#quick-start)
- [Configuration reference](#configuration-reference)
- [Defining job handlers](#defining-job-handlers)
- [Submitting jobs](#submitting-jobs)
  - [Basic submission](#basic-submission)
  - [With payload](#with-payload)
  - [Scheduled / delayed execution](#scheduled--delayed-execution)
  - [Custom priority](#custom-priority)
  - [Custom retry count](#custom-retry-count)
  - [Callback URL](#callback-url)
  - [Timeout](#timeout)
- [Idempotency](#idempotency)
- [Job priorities and weighted scheduling](#job-priorities-and-weighted-scheduling)
- [Job lifecycle and statuses](#job-lifecycle-and-statuses)
- [Cancelling jobs](#cancelling-jobs)
- [Retry mechanism](#retry-mechanism)
- [Rate limiting](#rate-limiting)
- [Worker profile (scheduler + maintenance)](#worker-profile-scheduler--maintenance)
- [Job events (listening)](#job-events-listening)
- [Monitoring and queue stats](#monitoring-and-queue-stats)
- [Execution logs](#execution-logs)
- [Demo application](#demo-application)
- [Running with Docker](#running-with-docker)
- [Building from source](#building-from-source)
- [License](#license)

## Add dependency

```xml
<dependency>
    <groupId>io.github.learnerview</groupId>
    <artifactId>simplydone4j-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick start

**1. Start Redis** (see [Running with Docker](#running-with-docker))

**2. Configure `application.yml`:**

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379
```

**3. Define a handler and submit a job:**

```java
@SpringBootApplication
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }

    @Bean
    public JobHandler emailHandler() {
        return ctx -> System.out.println("Sending email for job: " + ctx.getJobId());
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

**4. Run the worker profile** (enables scheduling & maintenance):

```bash
java -jar my-app.jar --spring.profiles.active=worker
```

## Configuration reference

All properties are under the `simplydone4j` prefix.

| Property | Default | Description |
|---|---|---|
| `simplydone4j.scheduler.polling-interval-ms` | `1000` | How often the scheduler polls queues |
| `simplydone4j.scheduler.queue-prefix` | `simplydone4j:queue` | Redis key prefix for queues |
| `simplydone4j.scheduler.weights.high` | `70` | Scheduling weight for HIGH priority |
| `simplydone4j.scheduler.weights.normal` | `20` | Scheduling weight for NORMAL priority |
| `simplydone4j.scheduler.weights.low` | `10` | Scheduling weight for LOW priority |
| `simplydone4j.rate-limit.requests-per-minute` | `60` | Max submissions per producer per window |
| `simplydone4j.rate-limit.window-seconds` | `60` | Rate limit sliding window in seconds |
| `simplydone4j.retry.max-attempts` | `3` | Max retry attempts (including first try) |
| `simplydone4j.retry.initial-delay-seconds` | `5` | Initial delay before first retry |
| `simplydone4j.retry.backoff-multiplier` | `2.0` | Multiplier applied to delay each attempt |
| `simplydone4j.worker.lease-timeout-seconds` | `30` | Max time a worker can hold a job lease |
| `simplydone4j.worker.retry-promoter-interval-ms` | `1000` | How often to move due retries back to QUEUED |
| `simplydone4j.worker.lease-reaper-interval-ms` | `5000` | How often to recover expired leases |
| `simplydone4j.queue.max-depth` | `10000` | Max total jobs across all priority queues |

Example with custom values:

```yaml
simplydone4j:
  scheduler:
    polling-interval-ms: 500
    queue-prefix: "myapp:queue"
    weights:
      high: 80
      normal: 15
      low: 5
  rate-limit:
    requests-per-minute: 120
    window-seconds: 30
  retry:
    max-attempts: 5
    initial-delay-seconds: 2
    backoff-multiplier: 3.0
  worker:
    lease-timeout-seconds: 60
    retry-promoter-interval-ms: 2000
    lease-reaper-interval-ms: 10000
  queue:
    max-depth: 50000
```

## Defining job handlers

A handler implements the `JobHandler` functional interface:

```java
@FunctionalInterface
public interface JobHandler {
    void handle(JobContext context) throws Exception;
}
```

The `JobContext` provides:

| Method | Description |
|---|---|
| `getJobId()` | Unique job identifier |
| `getJobType()` | The job type this handler was registered for |
| `getProducer()` | The producer name that submitted the job |
| `getPayload()` | Raw JSON payload string |
| `getAttemptCount()` | Current attempt number (0-based) |
| `getMaxAttempts()` | Maximum attempts configured for this job |

Register handlers via `HandlerRegistry`:

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
            String payload = ctx.getPayload();
            someService.sendEmail(payload);
        });

        registry.register("generate-report", ctx -> {
            someService.generateReport(ctx.getJobId());
        });

        registry.register("webhook", ctx -> {
            someService.callWebhook(ctx.getJobId(), ctx.getPayload());
        });
    }
}
```

## Submitting jobs

Inject `JobSubmissionService` and call `submit(producer, request)`.

### Basic submission

```java
JobSubmissionRequest req = new JobSubmissionRequest();
req.setJobType("send-email");
req.setIdempotencyKey("email-001");

JobSubmissionResponse res = submissionService.submit("my-app", req);
// res.getJobId()  -> UUID string
// res.getStatus() -> "QUEUED"
```

### With payload

```java
req.setPayload(Map.of(
    "to", "user@example.com",
    "subject", "Welcome!",
    "template", "welcome-email"
));
```

### Scheduled / delayed execution

Set `nextRunAt` to schedule the job for future execution:

```java
req.setNextRunAt(Instant.now().plusSeconds(3600)); // run in 1 hour
```

### Custom priority

```java
req.setPriority("HIGH");  // "HIGH", "NORMAL", or "LOW"
```

### Custom retry count

Override the default max attempts per job:

```java
req.setMaxAttempts(10);
```

### Callback URL

Specify a URL to be notified when the job completes:

```java
req.setCallbackUrl("https://myapp.com/webhooks/job-complete");
```

### Timeout

Set an execution timeout in seconds:

```java
req.setTimeoutSeconds(30);
```

The complete `JobSubmissionRequest`:

| Field | Required | Type | Description |
|---|---|---|---|
| `jobType` | Yes | String | Type matching a registered handler |
| `idempotencyKey` | Yes | String | Unique key per producer for deduplication |
| `priority` | No | String | `HIGH`, `NORMAL` (default), or `LOW` |
| `payload` | No | `Map<String, Object>` | Arbitrary data passed to the handler |
| `nextRunAt` | No | Instant | Schedule for future execution |
| `maxAttempts` | No | Integer | Per-job override of `simplydone4j.retry.max-attempts` |
| `timeoutSeconds` | No | Integer | Execution timeout |
| `callbackUrl` | No | String | URL for completion notification |

## Idempotency

SimplyDone4J guarantees idempotent submission. If you submit a job with the same `producer` + `idempotencyKey` as an existing job, the original job's details are returned instead of creating a duplicate:

```java
JobSubmissionRequest req = new JobSubmissionRequest();
req.setJobType("send-email");
req.setIdempotencyKey("order-456");

// First call — creates the job
JobSubmissionResponse res1 = submissionService.submit("my-app", req);

// Second call with same producer + idempotencyKey — returns existing job
JobSubmissionResponse res2 = submissionService.submit("my-app", req);
// res2.getJobId().equals(res1.getJobId()) -> true
```

## Job priorities and weighted scheduling

Three priority levels are supported:

| Priority | Default Weight | Description |
|---|---|---|
| `HIGH` | 70 | Critical jobs (e.g., password resets, payments) |
| `NORMAL` | 20 | Standard jobs (e.g., email notifications) |
| `LOW` | 10 | Background maintenance tasks |

The scheduler uses **deficit weighted round-robin** scheduling. Each priority accumulates deficit based on its weight. The priority with the highest deficit that has queued jobs wins the next poll cycle. This ensures higher priority queues are drained faster while lower priority queues still make progress.

## Job lifecycle and statuses

```
QUEUED → RUNNING → SUCCESS
  │         │
  │         └──→ RETRY_SCHEDULED → QUEUED → RUNNING → ...
  │                                                      │
  │                                                      ├──→ SUCCESS
  │                                                      └──→ DLQ (dead-letter)
  │
  └──→ CANCELLED
```

| Status | Description |
|---|---|
| `QUEUED` | Job is waiting in a priority queue for execution |
| `RUNNING` | Job has been claimed by a worker and is executing |
| `RETRY_SCHEDULED` | Job failed and is waiting for its next retry time |
| `SUCCESS` | Job completed successfully |
| `FAILED` | Job failed (intermediate status during retry) |
| `CANCELLED` | Job was cancelled by user request |
| `DLQ` | Dead-letter queue — job exceeded max retries |

## Cancelling jobs

Only jobs in `QUEUED` status can be cancelled:

```java
try {
    submissionService.cancelJob(jobId);
} catch (IllegalArgumentException e) {
    // Job is not in QUEUED status
}
```

Cancellation removes the job from its priority queue and sets the status to `CANCELLED`. A `JOB_CANCELLED` event is published.

## Retry mechanism

When a handler throws an exception, the retry service kicks in:

1. The failure is logged to the execution log
2. If `attemptCount < maxAttempts`:
   - Delay is calculated: `initialDelaySeconds * (backoffMultiplier ^ attemptCount)`
   - Job status is set to `RETRY_SCHEDULED` with the computed `nextRunAt`
   - The retry promoter (running every `retry-promoter-interval-ms`) moves due retries back to `QUEUED`
3. If `attemptCount >= maxAttempts`:
   - Job is moved to `DLQ` status with the error message as the result

Example retry timeline with defaults (max=3, delay=5s, multiplier=2.0):

| Attempt | Delay |
|---|---|
| 1st retry | 5s |
| 2nd retry | 10s |
| 3rd retry | 20s (moves to DLQ after failure) |

## Rate limiting

Each producer has a sliding window rate limit (default: 60 requests per 60 seconds). The rate limiter uses Redis sorted sets for accurate counting across distributed instances, with an in-memory fallback if Redis is unavailable.

```yaml
simplydone4j:
  rate-limit:
    requests-per-minute: 120
    window-seconds: 30
```

When exceeded, a `RateLimitExceededException` is thrown with a `retryAfterSeconds` hint.

## Worker profile (scheduler + maintenance)

The scheduler (`SchedulerEngine`) and maintenance services (`WorkerMaintenanceServiceImpl`) are behind the `@Profile("worker")` annotation. Activate the worker profile to enable:

- **Polling** — polls priority queues and claims ready jobs for execution
- **Retry promotion** — moves `RETRY_SCHEDULED` jobs past their delay back to `QUEUED`
- **Lease recovery** — detects expired leases on `RUNNING` jobs and triggers retry

```bash
java -jar my-app.jar --spring.profiles.active=worker
```

You can also run the application in **dual mode** (submission + worker) with the same profile.

## Job events (listening)

SimplyDone4J publishes Spring `ApplicationEvent`s for every job lifecycle transition. Listen to them by creating an `@EventListener`:

```java
@Component
public class JobEventListener {

    @EventListener
    public void onJobEvent(JobEventPublisher.JobPublishedEvent event) {
        switch (event.event()) {
            case JOB_CREATED -> log.info("Job created: {}", event.data().getJobId());
            case JOB_STARTED -> log.info("Job started: {}", event.data().getJobId());
            case JOB_COMPLETED -> log.info("Job completed: {} in {}ms",
                    event.data().getJobId(), event.data().getDurationMs());
            case JOB_FAILED -> log.warn("Job failed: {} reason={}",
                    event.data().getJobId(), event.data().getResult());
            case JOB_RETRY -> log.info("Job will retry: {} attempt {}/{}",
                    event.data().getJobId(), event.data().getAttempt(), event.data().getMaxAttempts());
            case JOB_CANCELLED -> log.info("Job cancelled: {}", event.data().getJobId());
        }
    }
}
```

The `JobEventData` provides:

| Method | Description |
|---|---|
| `getJobId()` | Job identifier |
| `getJobType()` | Job type |
| `getProducer()` | Producer name |
| `getStatus()` | Current status |
| `getPriority()` | Priority level |
| `getResult()` | Result or error message |
| `getAttempt()` | Current attempt number |
| `getMaxAttempts()` | Maximum attempts |
| `getDurationMs()` | Execution duration in milliseconds |
| `getTimestamp()` | Event timestamp |

## Monitoring and queue stats

Query job counts by status and priority through `JobRepository`:

```java
@Component
public class MonitoringService {

    private final JobRepository jobRepo;

    public MonitoringService(JobRepository jobRepo) {
        this.jobRepo = jobRepo;
    }

    public QueueStatsResponse getStats() {
        return QueueStatsResponse.builder()
                .highQueueSize(jobRepo.countByStatusAndPriority(JobStatus.QUEUED, JobPriority.HIGH))
                .normalQueueSize(jobRepo.countByStatusAndPriority(JobStatus.QUEUED, JobPriority.NORMAL))
                .lowQueueSize(jobRepo.countByStatusAndPriority(JobStatus.QUEUED, JobPriority.LOW))
                .totalQueued(jobRepo.countByStatus(JobStatus.QUEUED))
                .totalRunning(jobRepo.countByStatus(JobStatus.RUNNING))
                .totalSuccess(jobRepo.countByStatus(JobStatus.SUCCESS))
                .totalFailed(jobRepo.countByStatus(JobStatus.FAILED))
                .totalDlq(jobRepo.countByStatus(JobStatus.DLQ))
                .totalProcessed(jobRepo.countByStatus(JobStatus.SUCCESS)
                        + jobRepo.countByStatus(JobStatus.DLQ))
                .build();
    }
}
```

You can also fetch full job details:

```java
JobResponse job = submissionService.getJob(jobId);
// Access all fields via getters:
// job.getId(), job.getStatus(), job.getPriority(), job.getAttemptCount(),
// job.getStartedAt(), job.getCompletedAt(), job.getPayload(), etc.
```

## Execution logs

Every execution attempt is stored in Redis. Retrieve logs for a specific job:

```java
List<JobExecutionLog> logs = logRepository.findByJobIdOrderByAttemptAsc(jobId);
for (JobExecutionLog log : logs) {
    System.out.printf("Attempt %d: %s (%dms) - %s%n",
            log.getAttempt(), log.getStatus(), log.getDurationMs(), log.getMessage());
}
```

Each `JobExecutionLog` contains:

| Field | Description |
|---|---|
| `id` | Unique log entry ID |
| `jobId` | Job identifier |
| `attempt` | Attempt number |
| `status` | `SUCCESS` or `FAILED` |
| `message` | Success message or error message |
| `durationMs` | Execution duration |
| `executedAt` | When the attempt executed |

## Demo application

A complete demo Spring Boot application is available at [`simplydone4j-demo`](https://github.com/learnerview/simplydone4j-demo). It demonstrates:

- **4 job handlers**: `quick-success`, `failing-task`, `long-running`, `callback-test`
- **REST API**: submit, query, cancel jobs and view queue statistics
- **Auto-submission**: `DemoRunner` submits ~15 jobs at startup across all priorities
- **Job events**: lifecycle events logged via `DemoEventListener`
- **Rate limiting & idempotency**: built-in tests on startup

Run it:

```bash
cd simplydone4j-demo
mvn clean package
java -jar target/simplydone4j-demo-1.0.0.jar --spring.profiles.active=worker
```

Access `http://localhost:8080/api/jobs/stats` to see queue statistics.

## Running with Docker

**Start Redis:**

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

**With authentication:**

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --requirepass mypassword
```

```yaml
spring:
  data:
    redis:
      url: redis://:mypassword@localhost:6379
```

**With Docker Compose:**

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
      - REDIS_URL=redis://redis:6379
      - SPRING_PROFILES_ACTIVE=worker
    depends_on:
      - redis
```

The auto-configuration reads the `REDIS_URL` environment variable if set, falling back to `redis://localhost:6379`.

## Building from source

```bash
git clone https://github.com/learnerview/simplydone4j.git
cd simplydone4j
mvn clean install -DskipTests   # build the library
```

To build and run the demo:

```bash
cd simplydone4j-demo
mvn clean package
java -jar target/simplydone4j-demo-1.0.0.jar --spring.profiles.active=worker
```

The library is Java 21+ and Spring Boot 4.x compatible.

## License

MIT
