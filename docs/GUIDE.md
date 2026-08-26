# User Guide

This guide covers the core APIs and patterns for working with SimplyDone4J in your application.

---

## 1. Defining Job Handlers

Handlers implement the `JobHandler` functional interface:

```java
@FunctionalInterface
public interface JobHandler {
    String handle(JobContext context) throws Exception;
}
```

The return value (`String`) is stored as the job result and sent to webhooks/callbacks.

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
| `isCancellationRequested()` | Check if cancellation was requested |
| `requestCancellation()` | Request job cancellation |
| `getProgress()` | Get progress (0.0–1.0) if a `ProgressCallback` is set |
| `setProgress(double percent, String message)` | Set progress if a `ProgressCallback` is set |

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

### Callback URL

```java
req.setCallbackUrl("https://myapp.com/webhooks/job-complete");
```

### Timeout

```java
req.setTimeoutSeconds(30); // execution timeout
```

### Full Request Fields

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

**Important:** If the application crashes after winning the idempotency `SETNX` lock but before saving the job, the idempotency key remains consumed for the lock's TTL duration (default 1 hour). The caller receives no job ID and must retry after the TTL expires. This is a fundamental trade-off of optimistic deduplication without distributed transactions. Consider setting `idempotency-ttl-hours` appropriately for your tolerance.

---

## 3. Job Lifecycle

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

---

## 4. Priorities and Weighted Scheduling

Three priority levels with configurable weights:

| Priority | Default Weight | When to use |
|---|---|---|
| `HIGH` | 70 | Password resets, payments, time-sensitive |
| `NORMAL` | 20 | Email notifications, standard processing |
| `LOW` | 10 | Background maintenance, bulk operations |

The scheduler uses **deficit weighted round-robin**. Each priority accumulates deficit proportional to its weight. The priority with the highest deficit that has queued jobs wins the next poll. Higher-priority queues drain faster while lower-priority queues still make progress — no starvation.

---

## 5. Retry Mechanism

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

---

## 6. Rate Limiting

Per-producer sliding window rate limiter (default: 60 requests per 60 seconds). Uses **Redis sorted sets** for accuracy across distributed instances, with an **in-memory fallback** and **circuit breaker** if Redis is unavailable.

```yaml
simplydone4j:
  rate-limit:
    requests-per-minute: 120
    window-seconds: 30
```

When exceeded, `RateLimitExceededException` is thrown with a `retryAfterSeconds` hint.

**Circuit breaker:** If rate limit failures exceed `circuit-breaker.failures` (default 5), the circuit opens and subsequent requests immediately receive a `RateLimitExceededException` for `circuit-breaker.reset-seconds` (default 30s). After the timeout, the circuit transitions to half-open and allows a probe request through. A slow handler duration exceeding `slow-call-ms` (default 2000ms) also triggers the circuit.

---

## 7. Scheduling and Maintenance

Scheduling (`SchedulerEngine`) and maintenance services (`WorkerMaintenanceServiceImpl`) are enabled by default when Redis is configured. They provide:

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

---

## 8. Cancellation & Progress (New)

Jobs support cooperative cancellation and progress reporting via `JobContext`.

### Cancellation

```java
registry.register("long-task", ctx -> {
    for (int i = 0; i < 100; i++) {
        if (ctx.isCancellationRequested()) {
            throw new IllegalStateException("Job cancelled");
        }
        doWork(i);
    }
    return "done";
});
```

To cancel a job (only `QUEUED` jobs can be cancelled directly):

```java
submissionService.cancelJob(jobId);
```

For running jobs, cancellation is cooperative — the handler must poll `isCancellationRequested()`.

### Progress Reporting

```java
registry.register("progress-task", ctx -> {
    for (int i = 0; i <= 100; i += 10) {
        ctx.setProgress(i / 100.0, "Processing step " + i);
        Thread.sleep(500);
    }
    return "complete";
});
```

Progress is available via `MonitoringService.getJob(jobId)` or custom listeners via events.

---

## 8. Execution Logs (New)

Every execution attempt is logged to Redis with timing and outcome.

```java
List<JobExecutionLog> logs = jobExecutionLogRepository.findByJobIdOrderByAttemptAsc(jobId);
for (JobExecutionLog log : logs) {
    System.out.printf("Attempt %d: %s (%dms) — %s%n",
            log.getAttempt(), log.getStatus(), log.getDurationMs(), log.getMessage());
}
```

`JobExecutionLog` fields: `jobId`, `attempt`, `status`, `message`, `durationMs`, `executedAt`.

Max entries per job: `retention.max-execution-logs-per-job` (default 50). TTL: 7 days.

---

## 9. Monitoring

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

---

## 10. Webhooks

Set `callbackUrl` on submission to receive HTTP POST on completion:

```java
req.setCallbackUrl("https://myapp.com/webhooks/job-complete");
```

Payload: JSON with `jobId`, `jobType`, `status`, `result`, `attempt`, `maxAttempts`, `durationMs`, `timestamp`. Retries on non-2xx with exponential backoff (max 3 attempts).