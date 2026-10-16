# Production Guide

Runtime behavior and hardening for a SimplyDone4J deployment. For how to package, provision
Redis and ship the application, see [DEPLOYMENT.md](DEPLOYMENT.md); for an honest, scored
readiness assessment, see [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md).

---

## 1. Graceful Shutdown

The worker pool uses `ThreadPoolExecutor.AbortPolicy` — **not** `CallerRunsPolicy`. This is
deliberate: submissions happen on the shared `@Scheduled` thread, and `CallerRuns` would run a
handler inline on that thread whenever the pool is saturated, stalling scheduling, retry
promotion and lease reaping together. A rejected dispatch is safe because `SchedulerEngine`
re-enqueues the claimed job (`executeClaimedJobOrRestore`), so it stays on the queue instead of
being lost.

On shutdown the executor waits for in-flight handlers before forcing termination:

```yaml
simplydone4j:
  executor:
    await-termination-seconds: 60
```

`waitForTasksToCompleteOnShutdown` is enabled by the starter; the wait is bounded by
`await-termination-seconds` (default `30`). Size it above your longest expected handler to avoid
cutting work off.

---

## 2. Resource Sizing

| Component | Guidance |
|---|---|
| Thread pool | `core-pool-size` ≈ CPU cores × 2; `max-pool-size` absorbs bursts. |
| Queue capacity | The executor's work-queue capacity (default 100) is separate from `queue.max-depth`. Keep the executor pool large enough that rejecting a *claimed* job is rare — a rejection is recovered by re-enqueue, but it adds a polling-latency round trip. |
| Queue admission | `queue.max-depth` (default 10000) is enforced **per priority queue**. A worst-case backlog of `3 × max-depth` is possible across the three priorities; size Redis accordingly and watch per-priority depths. |
| Redis memory | Roughly ~1 KB per job hash (estimate; payload-dependent). Execution-log lists only exist when `retention.store-execution-logs` is enabled (off by default). Finished jobs and their status-index members carry the retention TTL, so the retention window bounds total growth; the fencing counter is a single key and does not grow. |

The exact per-job footprint depends on payload size, so treat these as order-of-magnitude
guidance and measure your own workload.

---

## 3. Health Checks

SimplyDone4J ships a `SimplyDoneHealthIndicator`, registered as an Actuator health contributor
named **`simplyDone`** (Spring Boot derives the name from the bean `simplyDoneHealthIndicator` by
stripping the `HealthIndicator` suffix). It reports `UP` while work drains normally and `DOWN`
once a threshold is crossed or a repository call fails:

| Property | Default | Meaning |
|---|---|---|
| `simplydone4j.health.enabled` | `true` | Register the indicator (gated on Actuator + health on the classpath) |
| `simplydone4j.health.dead-letter-threshold` | `1` | DLQ count at or above reports `DOWN` (`0` disables the check) |
| `simplydone4j.health.max-queue-depth` | `100000` | **Total** depth across all priority queues above this reports `DOWN` (`0` disables the check) |

Set a threshold to `0` to disable that check (for example, repository-only health without
alerting on a busy queue). The indicator exposes `queueDepths`, `totalQueued`, `running`,
`deadLettered`, `deadLetterThreshold` and `maxQueueDepth` as health details.

Note the deliberate asymmetry: **admission (`queue.max-depth`) is per priority queue**, while
**the health threshold (`health.max-queue-depth`) is a total across all priorities**.

After a Redis outage the indicator reports `DOWN` with the exception recorded rather than
propagating it, so the health endpoint stays responsive and returns 503 instead of 500. To add
your own conditions (for example a success-rate floor), write a custom `HealthIndicator` that
also injects `MonitoringService`:

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
        if (stats.getTotalDlq() > 100) {
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

---

## 4. Metrics

### Micrometer / Prometheus

Add `micrometer-core` (and `micrometer-registry-prometheus` for Prometheus). SimplyDone4J
registers its own instrumentation — no manual binding required:

- **Counters:** `simplydone4j.jobs.submitted`, `simplydone4j.jobs.submitted.by.priority`
  (tag `priority`), `simplydone4j.jobs.claimed`, `simplydone4j.jobs.completed` (tag `outcome` =
  `SUCCESS`/`FAILED`/`TIMEOUT`/`DEAD_LETTER`/`DISCARDED`/`DEFERRED`),
  `simplydone4j.fencing.rejections`, `simplydone4j.lease.reaped`, `simplydone4j.jobs.overlapped`
- **Timers:** `simplydone4j.scheduler.poll`, `simplydone4j.jobs.duration` (tag `outcome`)
- **Distribution summary:** `simplydone4j.scheduler.claims.per.poll`
- **Gauges** (on by default; set `simplydone4j.metrics.queue-depth=false` to skip sampling):
  `simplydone4j.queue.depth` (tag `priority` = `HIGH`/`NORMAL`/`LOW`/`all`) and
  `simplydone4j.jobs.dead.letter`, refreshed every `queue-depth-refresh-seconds` (default 30s)
  on a timer — never on the scrape path, so a slow Redis cannot stall an Actuator scrape.

An operator note on the reasons the counters exist: a steady trickle of
`simplydone4j.fencing.rejections` is normal after a deploy; a sustained stream means leases are
expiring while work is still running. `simplydone4j.jobs.overlapped` climbing means the
uniqueness defer window is shorter than the job's real duration.

### Actuator Endpoints

Enable via `spring-boot-starter-actuator`:

| Endpoint | Description |
|---|---|
| `actuator/health` | Health incl. the `simplyDone` contributor |
| `actuator/info` | Application info with build metadata |
| `actuator/metrics` | `simplydone4j.*` meters |
| `actuator/prometheus` | Prometheus format (with `micrometer-registry-prometheus`) |

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

---

## 5. Debugging Checklist

1. `redis-cli ping` — check Redis connectivity.
2. `redis-cli ZCARD simplydone4j:queue:high` (and `:normal`, `:low`) — verify queues exist and
   check per-priority depth against `queue.max-depth`.
3. Check `simplydone4j.scheduler.enabled=true` if submitted jobs are never picked up.
4. `redis-cli HGETALL simplydone4j:job:<jobId>` — inspect the job record and status.
5. Watch `simplydone4j.fencing.rejections` and `simplydone4j.lease.reaped` to tell whether slow
   handlers are outliving `worker.lease-timeout-seconds`.
6. Monitor the thread pool's active count via `actuator/metrics`.
7. `redis-cli CLIENT LIST` — check connected clients.
8. Verify `simplydone4j.key-prefix` if multiple environments share one Redis.

For an orphaned `QUEUED` job with no queue entry (a crash between the job-hash write and the
queue push), re-submit it: the engine intentionally does not scan `QUEUED` state, so nothing
will pick it up automatically.
