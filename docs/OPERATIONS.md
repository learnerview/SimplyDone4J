# Operations Guide

This guide covers health checks, alerting, Redis failure behavior, capacity
planning, dead-letter recovery, rolling deployments, and troubleshooting for
SimplyDone4J. It assumes the requirements in the [README](../README.md#requirements)
and the execution contract in [Guarantees](GUARANTEES.md).

---

## Health checks

`EngineStatusService` performs a Redis `PING` and reads local scheduler and
maintenance state. It is auto-configured and does not require Actuator or
Micrometer.

```java
import io.github.learnerview.simplydone4j.model.EngineStatus;
import io.github.learnerview.simplydone4j.service.EngineStatusService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JobEngineHealthController {

    private final EngineStatusService engineStatus;

    JobEngineHealthController(EngineStatusService engineStatus) {
        this.engineStatus = engineStatus;
    }

    @GetMapping("/internal/job-engine/health")
    ResponseEntity<Map<String, Object>> health() {
        EngineStatus status = engineStatus.status();
        Map<String, Object> body = Map.of(
                "redisReachable", status.redisReachable(),
                "schedulerEnabled", status.schedulerEnabled(),
                "schedulerRunning", status.schedulerRunning(),
                "maintenanceRunning", status.maintenanceRunning(),
                "workersInFlight", status.workersInFlight(),
                "workerCapacity", status.workerCapacity(),
                "queueDepths", status.queueDepths());

        return status.healthy()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(503).body(body);
    }
}
```

`healthy()` is true when Redis is reachable and, if scheduling is enabled, both
the scheduler loop and the maintenance loop are running. Queue depth is reported
for diagnostics but does not make the engine unhealthy.

### Readiness and liveness

- Use `healthy()` for **readiness** when the instance must accept submissions or
  run jobs. A submission-only node (`scheduler.enabled=false`) still needs Redis
  for submissions.
- Do **not** use Redis or `healthy()` for **liveness**. Restarting an application
  does not fix Redis, and a restart can turn a short Redis outage into a rolling
  restart loop.
- Bound the probe latency. Sentinel/Cluster commands use
  `simplydone4j.redis.command-timeout-ms` (default 5s). For standalone Redis, set
  `spring.data.redis.timeout` so `PING` fails fast instead of waiting for a TCP
  timeout.
- Keep probe frequency and timeouts coordinated with your platform. A 5s Redis
  command timeout is too slow for a 1s probe deadline.

---

## Alerting

A useful starting point:

| Signal | Suggested action |
|---|---|
| `redisReachable=false` for two consecutive checks | Page or open an incident; submissions and scheduling are unavailable |
| `schedulerRunning=false` while `schedulerEnabled=true` | Investigate the scheduler thread; the process may be shutting down or stuck |
| `maintenanceRunning=false` while scheduling is enabled | Investigate the maintenance pool; retries, lease recovery, and orphan repair are not running |
| `workersInFlight == workerCapacity` for a sustained period | Inspect queue depth, handler latency, and Redis latency; scale workers or reduce work per job |
| One `queueDepths` value growing without a matching drain rate | Inspect the handler for that priority, retry volume, and Redis latency |
| `DeadLetterService.count()` rising | Run the DLQ review procedure below |

There is no built-in metric registry. Use the health values above, a
`@Scheduled` logger, or your application's Micrometer/Actuator integration. The
library deliberately does not add those dependencies.

A scheduled poller keeps the check off the request path:

```java
@Scheduled(fixedDelay = 15_000)
void logEngineStatus() {
    EngineStatus status = engineStatus.status();
    if (!status.healthy()) {
        log.warn("Job engine unhealthy: redis={} scheduler={} maintenance={} inFlight={}/{}",
                status.redisReachable(), status.schedulerRunning(),
                status.maintenanceRunning(), status.workersInFlight(),
                status.workerCapacity());
    }
}
```

---

## Redis failure behavior

Redis is a hard dependency. There is no in-memory fallback.

- **New submissions fail** while Redis is unavailable or the command timeout is
  exceeded.
- **Scheduler and maintenance loops keep running and log errors.** When Redis
  returns, polling and maintenance resume.
- **A handler may already be executing when Redis fails.** Its outcome write is
  fenced and can fail. If the write does not complete, the lease eventually
  expires and the job becomes runnable again, so the handler can run more than
  once. Keep handlers idempotent.
- **Failover can lose an acknowledged write.** A single Redis primary gives a
  single-node durability boundary. If writes must survive failover, configure
  Redis `min-replicas-to-write` and `min-replicas-max-lag` and expect writes to
  fail while the replica count is below the threshold. The library does not
  turn a single-node guarantee into a replicated one.
- **Clock skew affects scheduling.** Queue scores are based on application time.
  Keep hosts synchronized; large skew can make delayed jobs run early or late.

### Standalone Redis

Use Spring Boot's native connection properties:

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379
      timeout: 2s
```

Set a timeout that is shorter than your readiness probe deadline. Connection
pooling for standalone uses `spring.data.redis.lettuce.pool.*`.

### Sentinel

```yaml
simplydone4j:
  redis:
    sentinel-master: mymaster
    sentinel-nodes:
      - sentinel-1:26379
      - sentinel-2:26379
      - sentinel-3:26379
    password: ${REDIS_PASSWORD}
    command-timeout-ms: 5000
```

The library builds a pooled Lettuce factory and applies `redis.password` to the
Redis data connection. If the Sentinel nodes themselves require separate
authentication, or if the deployment requires TLS, provide your own
`RedisConnectionFactory` bean; the built-in factory does not add those settings.

### Cluster

```yaml
simplydone4j:
  key-prefix: "simplydone4j:{jobs}"
  redis:
    cluster-mode: true
    sentinel-nodes:
      - redis-1:6379
      - redis-2:6379
      - redis-3:6379
    password: ${REDIS_PASSWORD}
```

The hash tag is required because the queue and status scripts touch multiple
keys atomically. Without it, Redis Cluster rejects the scripts. The same hash tag
must be used by every instance that shares the job data.

---

## Capacity planning

Start with the defaults and measure before changing them.

- **Worker concurrency** is `executor.max-pool-size` (default 8). The scheduler
  will not claim more work than the worker semaphore permits.
- **Maintenance concurrency** is `worker.maintenance-pool-size` (default 3), one
  thread each for retry promotion, lease recovery, and orphan reconciliation.
- **Queue admission** is capped by `queue.max-depth` (default 10,000) across all
  priority queues. Claim restoration can briefly exceed it by the number of
  in-flight workers.
- **Lease duration** should exceed the slowest normal handler plus expected Redis
  latency. A lease that expires during a healthy handler lets another worker
  start the same job.
- **Handler timeout** is cooperative. A handler that ignores interruption keeps
  its thread and lease renewal until it returns, so treat
  `executor.default-timeout-seconds` as a backstop rather than a hard kill.
- **Redis connections** are consumed by normal commands and by dedicated
  `WATCH/MULTI` connections. Size `redis.pool-max-total` for concurrent
  submissions, claims, and lease renewals. Pool exhaustion waits up to
  `redis.pool-max-wait-ms` before failing.
- **Payload size** is capped by `submission.max-payload-bytes` (default 1 MiB).
  Large payloads increase Redis memory, network time, and log size.

The load test in [Verification](VERIFICATION.md) is a burst check, not a soak
test. Run a test with your payload sizes, handler durations, Redis topology, and
failure settings before choosing production limits.

---

## Dead-letter recovery

Jobs that exhaust their retry budget move to `DLQ`. They stay in the status index;
there is no automatic replay or discard policy.

1. Inspect the oldest entries:

   ```java
   List<JobResponse> batch = deadLetterService.list(50);
   long total = deadLetterService.count();
   ```

2. Read the execution logs and payload. Determine whether the failure was
   transient, a bad payload, or a handler bug. Fix the cause before replaying.
3. Replay only when the handler is safe to run again:

   ```java
   deadLetterService.replay(jobId);
   ```

   Replay resets attempts and re-admits through the capacity-checked queue path.
   If the queue is full, the record is restored to `DLQ` and `QueueFullException`
   is thrown. If another operator already replayed or discarded the job, the
   compare-and-set loses and the call throws `IllegalStateException`.
4. Discard only when the work must never run again:

   ```java
   deadLetterService.discard(jobId);
   ```

   Discard moves `DLQ -> FAILED` before deleting, so it cannot delete a job that a
   concurrent replay has already returned to the queue. If deletion fails, the
   record is written back to `DLQ`.

Replay is at-least-once. If the original attempt already caused an external side
effect, the handler must be idempotent or use an outbox. Replay does not undo
prior effects.

---

## Rolling deployments

- Running the scheduler on every instance is supported. Claims are atomic, and
  lease fencing prevents a superseded worker from recording an outcome.
- Set `scheduler.enabled=false` on instances that should only submit jobs.
- Leave enough time in the shutdown grace period for handlers that are allowed to
  finish. The executor waits up to `executor.await-termination-seconds` (default
  30s) for in-flight work. The auto-configured executor depends on the timeout
  scheduler, so the executor shuts down first and watchdog enforcement and lease
  renewal remain available while in-flight jobs drain.
- Do not change the internal serialization shape while old job records still
  exist. The internal `simplyDoneObjectMapper` keeps application-wide Jackson
  settings out of the stored format, but changing field names makes existing
  records unreadable.
- Deploy the same handler registrations to every instance that can execute the
  job types. A worker that claims an unknown type will fail the attempt.
- Keep Redis and application clocks synchronized with NTP.

---

## Troubleshooting

| Symptom | Checks |
|---|---|
| `No handler registered for jobType` | Register handlers during context initialization; confirm every executing instance has the same registrations; check for a typo in the job type |
| Submissions return `QueueFullException` | Check `queue.max-depth`, the drain rate, handler latency, and whether retries are accumulating |
| Jobs stay `QUEUED` | Check queue membership, `worker.orphan-grace-ms`, `worker.orphan-reconcile-interval-ms`, and scheduler health |
| Jobs stay `RUNNING` longer than expected | Check handler interruption behavior, lease renewal, Redis latency, and `worker.lease-timeout-seconds` |
| Retries are delayed or not appearing | Check `nextRunAt`, `worker.retry-promoter-interval-ms`, promoter errors, Redis latency, and clock skew |
| `EngineStatusService` returns unhealthy | Check Redis reachability, scheduler loop state, and maintenance loop state; a full queue is not an unhealthy state |
| Duplicate side effects | Execution is at-least-once; add an idempotency key, an outbox, or a deduplicating external operation |
| Replay throws `IllegalStateException` | The job is no longer `DLQ`; another operator or process changed it first |
| Replay throws `QueueFullException` | Reduce queue depth or drain work first; the job remains in `DLQ` |
| Redis connection pool exhaustion | Raise `redis.pool-max-total` or reduce concurrent claims/submissions; check for slow Redis commands |

---

## Security

- Treat Redis as part of the application's trusted network. The library does not
  encrypt job payloads or Redis traffic by itself.
- Use Redis authentication and ACLs, restrict network access, and use TLS when
  traffic crosses an untrusted boundary. Standalone TLS can be configured through
  Spring Boot; the built-in Sentinel/Cluster factory does not add TLS, so provide
  a custom `RedisConnectionFactory` when that is required.
- Use `redis.password` from an environment variable or secret manager rather than
  committing it to configuration files.
- Use a distinct Redis instance, database, or `key-prefix` per environment.
- Payloads and execution-log errors are stored in Redis. Do not put secrets in
  payloads or exception messages.
- The library logs lifecycle events and failures. Review log destinations and
  retention as part of your normal data-handling policy.
