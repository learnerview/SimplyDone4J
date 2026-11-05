# User Guide

This guide covers the core APIs and patterns for working with SimplyDone4J in your application. See the [README](../README.md#requirements) for requirements and setup, or the [Operations guide](OPERATIONS.md) for health checks, failover, and DLQ procedures.

---

## 0. Public API Surface

Every supported bean is auto-configured with `@ConditionalOnMissingBean`, so an
application can inject it or replace it with its own implementation.

| Bean / type | Purpose |
|---|---|
| `JobSubmissionService` | `submit(producer, request)`, `getJob(jobId)`, `cancelJob(jobId)` |
| `HandlerRegistry` | `register(jobType, handler)`, `getHandler(jobType)`, `getHandlers()` |
| `JobExecutionLogRepository` | `findByJobIdOrderByAttemptAsc(jobId)` |
| `JobQueryRepository` | `findById`, `findByProducerAndStatus`, `findByStatus`, `findByStatus(status, limit)`, `countByStatus`, `countByStatusAndPriority` |
| `DeadLetterService` | `list()`, `list(limit)`, `count()`, `replay(jobId)`, `discard(jobId)` |
| `EngineStatusService` | `status()`, `isHealthy()` — Redis reachability, loop state, queue depth, worker capacity |
| `JobEventPublisher` | Publishes lifecycle events; listen for `JobEventPublisher.JobPublishedEvent` |
| `SimplyDoneProperties` | Typed view of every `simplydone4j.*` property |
| `RetryPolicy` | Replace to change backoff; `ExponentialBackoffRetryPolicy` is the default |
| `JobHandler` | Application callback invoked for a registered job type |
| `JobContext` | Read-only handler view of one attempt |

`JobSubmissionRequest` is a mutable DTO. `JobSubmissionResponse` contains the
job id, status, type, priority, and scheduled time. `JobResponse` exposes the
full persisted view: id, type, producer, idempotency key, status, priority,
payload, result, scheduling and lease timestamps, attempt count, max attempts,
and creation/update timestamps.

The `Redis*` repositories and `service.impl.*` classes are implementation types.
They are public so the separate auto-configuration package can construct them and
so tests can exercise them, but applications should depend on the interfaces in
the table above. Do not instantiate or subclass an implementation class.

---

## 1. Defining Job Handlers

Handlers implement the `JobHandler` functional interface:

```java
@FunctionalInterface
public interface JobHandler {
    String handle(JobContext context) throws Exception;
}
```

The return value (`String`) is stored as the job result.

### JobContext API

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

### Handler Idempotency

Execution is at-least-once. A worker can complete an external side effect and then fail
before its terminal write, so the lease reaper will make the job runnable again. Handlers
that send email, charge a payment, or mutate another system must therefore be idempotent,
for example by using the job id as an idempotency key or by writing to an outbox in the
same transaction as the side effect. Do not assume a handler runs exactly once.

### Registering Handlers

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

Register handlers during context initialization, as above or from a bean initializer. The
scheduler starts on `ApplicationReadyEvent`, after `CommandLineRunner` and
`ApplicationRunner` beans have completed.

---

## 2. Submitting Jobs

Inject `JobSubmissionService` and call `submit(producer, request)`.

### Minimal Request

```java
JobSubmissionRequest req = new JobSubmissionRequest();
req.setJobType("send-email");
req.setIdempotencyKey("email-001");

JobSubmissionResponse res = submissionService.submit("my-app", req);
// res.getJobId() → UUID, res.getStatus() → "QUEUED"
```

### With Payload

```java
req.setPayload(Map.of(
    "to", "user@example.com",
    "subject", "Welcome!",
    "template", "welcome-email"
));
```

### Scheduled Execution

```java
req.setNextRunAt(Instant.now().plusSeconds(3600)); // run in 1 hour
```

### Custom Priority

```java
req.setPriority("HIGH"); // "HIGH", "NORMAL" (default), or "LOW"
```

### Custom Retry Count

```java
req.setMaxAttempts(10);
```

### Timeout

```java
req.setTimeoutSeconds(30); // execution timeout
```

Timeout is cooperative. When the deadline passes, the watchdog marks the attempt
as timed out and interrupts the worker thread. A handler that honours interruption
stops promptly; a handler that ignores it keeps its thread and its lease renewal
until it returns. Use the timeout as a backstop for well-behaved handlers, not as
a hard kill for arbitrary blocking code.

### Full Request Fields

| Field | Required | Type | Description |
|---|---|---|---|
| `jobType` | Yes | String | Matches a registered handler |
| `idempotencyKey` | Yes | String | Deduplication key per producer |
| `priority` | No | String | `HIGH`, `NORMAL` (default), `LOW` |
| `payload` | No | `Map<String, Object>` | Arbitrary data passed to the handler. Serialized payloads are capped by `submission.max-payload-bytes` (default 1 MiB). |
| `nextRunAt` | No | Instant | Delay execution until this instant |
| `maxAttempts` | No | Integer | Per-job override |
| `timeoutSeconds` | No | Integer | Execution timeout |

### Submission Errors

| Exception | Cause |
|---|---|
| `QueueFullException` | Atomic admission found the global queue depth at `queue.max-depth`; the job record and idempotency reservation are rolled back |
| `IllegalArgumentException` | Bean Validation failed, `producer` is blank, the serialized payload exceeds `submission.max-payload-bytes`, or a job id is unknown where an id is required |
| `JobNotFoundException` | `getJob`/`cancelJob` was given an id that does not exist |
| `IllegalStateException` | A duplicate idempotency key is still being created by another submission |

---

## 3. Idempotency

Submitting the same `producer + idempotencyKey` returns the existing job instead of creating a duplicate:

```java
JobSubmissionRequest req = new JobSubmissionRequest();
req.setJobType("send-email");
req.setIdempotencyKey("order-456");

JobSubmissionResponse res1 = submissionService.submit("my-app", req);
JobSubmissionResponse res2 = submissionService.submit("my-app", req);
// res2.getJobId().equals(res1.getJobId()) → true
```

**Important:** If the application crashes after taking the idempotency reservation but before saving the job, the reservation remains `pending:<jobId>` until its TTL expires (default 1 hour). The caller receives no job ID and must retry after the TTL expires. This is a fundamental trade-off of optimistic deduplication without distributed transactions. Consider setting `idempotency-ttl-hours` appropriately for your tolerance.

---

## 4. Job Lifecycle

```
QUEUED → RUNNING ──→ SUCCESS
  │         │
  │         ├──→ RETRY_SCHEDULED → QUEUED (loop)
  │         │
  │         └──→ DLQ (max retries exceeded) ──→ QUEUED (replay)
  │                              │
  │                              └──→ deleted (discard)
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
| `FAILED` | Reserved status value; retry exhaustion uses `DLQ` |

---

## 5. Priorities and Weighted Scheduling

Three priority levels with configurable weights:

| Priority | Default Weight | When to use |
|---|---|---|
| `HIGH` | 70 | Password resets, payments, time-sensitive |
| `NORMAL` | 20 | Email notifications, standard processing |
| `LOW` | 10 | Background maintenance, bulk operations |

The scheduler uses **deficit weighted round-robin**. Each priority accumulates deficit proportional to its weight. The priority with the highest deficit that has queued jobs wins the next poll. Higher-priority queues drain faster while lower-priority queues still make progress — no starvation.

---

## 6. Retry Mechanism

When a handler throws:

1. The failure is recorded in the execution log
2. If `attempt + 1 < maxAttempts` (retries remain):
   - Base delay = `initialDelaySeconds × (backoffMultiplier ^ attemptCount)`
   - The actual delay is spread by `±jitterFactor` around that base (default `0.1`)
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

---

## 7. Scheduling and Maintenance

Scheduling (`SchedulerEngine`) and maintenance services (`WorkerMaintenanceServiceImpl`) are enabled by default when Redis is configured. They start on `ApplicationReadyEvent` and provide:

- **Polling** — polls priority queues and claims ready jobs using deficit round-robin
- **Retry promotion** — moves due `RETRY_SCHEDULED` jobs back to `QUEUED`
- **Lease recovery** — detects expired leases on `RUNNING` jobs, triggers retry with token fencing

**To run a submission-only node (no scheduling):**

```bash
java -jar my-app.jar --simplydone4j.scheduler.enabled=false
```

---

## 8. Job Events

SimplyDone4J publishes Spring `ApplicationEvent`s for every lifecycle transition:

```java
import io.github.learnerview.simplydone4j.event.JobEventPublisher.JobPublishedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
            case JOB_REPLAYED -> log.info("Dead-lettered job replayed: {}", event.data().getJobId());
            case JOB_DISCARDED -> log.info("Dead-lettered job discarded: {}", event.data().getJobId());
        }
    }
}
```

`JobEventData` fields: `jobId`, `jobType`, `producer`, `status`, `priority`, `result`,
`attempt`, `maxAttempts`, `durationMs`, `timestamp`, and `additionalData`. Listener
exceptions are logged and do not change the recorded job outcome.

---

## 9. Cancellation

Cancellation is supported for jobs that are still `QUEUED`, including delayed jobs:

```java
submissionService.cancelJob(jobId);
```

Cancellation races the scheduler through an atomic Redis compare-and-set. Running jobs are
not force-stopped; use a bounded timeout and make handlers respond to thread interruption.

---

## 10. Execution Logs

Every execution attempt is logged to Redis with timing and outcome.

```java
List<JobExecutionLog> logs = jobExecutionLogRepository.findByJobIdOrderByAttemptAsc(jobId);
for (JobExecutionLog log : logs) {
    System.out.printf("Attempt %d: %s (%dms) — %s%n",
            log.getAttempt(), log.getStatus(), log.getDurationMs(), log.getMessage());
}
```

`JobExecutionLog` fields: `jobId`, `attempt`, `status`, `message`, `durationMs`, `executedAt`.

Max entries per job: `retention.max-execution-logs-per-job` (default 50). TTL:
`retention.execution-log-ttl-days` (default 7 days). Set
`retention.store-execution-logs=false` to stop writing logs.

---

## 11. Fetching Job Details

Retrieve one job by id:

```java
JobResponse job = submissionService.getJob(jobId);
```

For operational counts, inject `JobQueryRepository`:

```java
long queued = jobs.countByStatus(JobStatus.QUEUED);
long highQueued = jobs.countByStatusAndPriority(JobStatus.QUEUED, JobPriority.HIGH);
List<JobEntity> deadLettered = jobs.findByStatus(JobStatus.DLQ);
List<JobEntity> producerFailures = jobs.findByProducerAndStatus("checkout", JobStatus.DLQ);
```

Status-count reads also prune terminal index members that are older than the
retention window, so they stay consistent without a background sweeper.

---

## 12. Dead-Letter Recovery

Jobs that exhaust `retry.max-attempts` move to `DLQ`. They are not in a separate Redis
queue; `DeadLetterService` reads them from the status index and applies an explicit
transition.

```java
@Autowired DeadLetterService deadLetters;

List<JobResponse> batch = deadLetters.list(50);
long total = deadLetters.count();

// Reset attempts and put the job back in its priority queue.
JobResponse requeued = deadLetters.replay(jobId);

// Permanently delete a job that should never run again.
deadLetters.discard(jobId);
```

`replay` atomically compares the persisted status against `DLQ` before writing the reset
record, then admits the job through the same capacity-checked queue path as a submission.
If the queue is full it restores the `DLQ` record and throws `QueueFullException`. If
another operator already replayed or discarded the job, the compare-and-set loses and the
call throws `IllegalStateException`. Ambiguous queue admission is resolved by checking
queue membership; if membership cannot be determined the record stays `QUEUED` for orphan
reconciliation.

`discard` first moves the job from `DLQ` to `FAILED` and only then deletes it. A concurrent
replay therefore wins or the delete wins, never both. If deletion fails, the job is written
back to `DLQ` so it remains visible for manual recovery.

**Replay is at-least-once.** A replayed job can run after its original attempt already
performed an external side effect. Keep handlers idempotent or use an outbox.

---

## 13. Engine Status

`EngineStatusService` is a small, pull-based check that does not depend on Actuator or
Micrometer:

```java
@Autowired EngineStatusService engineStatus;

EngineStatus status = engineStatus.status();
if (!status.healthy()) {
    log.warn("Engine unhealthy: redis={} scheduler={} maintenance={}",
            status.redisReachable(), status.schedulerRunning(), status.maintenanceRunning());
}
```

The status includes Redis reachability (a `PING` per call), whether the scheduler and
maintenance loops are running, current worker in-flight/capacity, and queue depth per
priority. Queue depth is diagnostic only: a full queue is backpressure, not an unhealthy
process, so `healthy()` does not fail on depth. When `scheduler.enabled=false`, loop state
is ignored.

Call it from your own health endpoint, a scheduled logger, or an alerting integration.
See [Operations](OPERATIONS.md) for probe examples, alert thresholds, and failover
behavior.

---
