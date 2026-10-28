# Operations

The engine is a library, so "operating" it means operating your application and its Redis.
This page covers the signals to watch, the routine tasks, and the incident playbooks.

## Signals

### Metrics

Enable Micrometer (it is on by default when `micrometer-core` is present) and scrape your
usual endpoint.

| Metric | Type | Watch for |
| --- | --- | --- |
| `simplydone4j.jobs.submitted` | counter | Submission rate, by producer. |
| `simplydone4j.jobs.claimed` | counter | Claim rate. A gap against submissions means the queue is growing. |
| `simplydone4j.jobs.completed` | counter | Completions. Compare with claims to see throughput. |
| `simplydone4j.jobs.duration` | timer | Handler latency, including the slow tail. |
| `simplydone4j.jobs.submitted.by.priority` | counter | Priority mix. |
| `simplydone4j.jobs.overlapped` | counter | Jobs deferred by a uniqueness lock. |
| `simplydone4j.fencing.rejections` | counter | Outcomes discarded because the lease was lost. |
| `simplydone4j.lease.reaped` | counter | Leases that expired. |
| `simplydone4j.scheduler.poll` | timer | Poll latency. |
| `simplydone4j.scheduler.claims.per.poll` | distribution | Claim batch size. |

### Health

With Actuator on the classpath, `GET /actuator/health` includes a `simplyDone` component:

```json
{
  "status": "UP",
  "details": {
    "queueDepths": { "HIGH": 0, "NORMAL": 3, "LOW": 12 },
    "totalQueued": 15,
    "running": 4,
    "deadLettered": 0,
    "deadLetterThreshold": 1,
    "maxQueueDepth": 100000
  }
}
```

It reports `DOWN` when the DLQ reaches `health.dead-letter-threshold` or total queue depth
exceeds `health.max-queue-depth`, and also when Redis is unreachable. A threshold of `0`
disables that individual check.

Decide deliberately whether these conditions should fail readiness. A DLQ that is being
worked through is not a reason to stop serving traffic; a queue that never drains is.

### Monitoring service

For dashboards or an internal admin page, inject `MonitoringService`:

```java
QueueStatsResponse stats = monitoringService.getStats();
Map<String, Long> byStatus = monitoringService.getCountByStatus();
Map<String, Long> depths = monitoringService.getQueueDepths();
```

### Logs

The engine logs at `INFO` for lifecycle transitions and `WARN` for recoverable problems
(rejected dispatch, fenced write). Sustained `WARN` lines are the early
signal; the metrics are the aggregate.

Operational failures that the engine cannot recover from (scheduler poll failure, dispatch
or restore failure, terminal write failure) are logged at `ERROR`. Recoverable conditions
that warrant operator attention are logged at `WARN`. Where a cause exists, the throwable is
passed to the logger so the full stack trace is emitted; the engine deliberately avoids
logging bare `e.getMessage()` values into parameterized messages, because that renders the
message text without the failure context.

The starter ships no logging configuration of its own; levels and output are owned by the
host application through standard Spring Boot logging. Set
`logging.level.io.github.learnerview.simplydone4j=DEBUG` to get engine diagnostics.

## Alerts worth having

- `deadLettered` above zero and rising — work is failing terminally.
- `totalQueued` rising for longer than one processing cycle — workers cannot keep up.
- `simplydone4j.lease.reaped` non-zero for more than a few minutes — handlers outlive
  their lease, or a worker is wedged.
- `simplydone4j.fencing.rejections` sustained — duplicate execution attempts.
- Redis memory above 80% with `maxmemory-policy noeviction` — submissions will start
  failing when memory fills.
- Health `DOWN` — Redis unreachable or a threshold breached.

## Routine tasks

### Inspecting the queue

```java
jobClient.countByStatus(JobStatus.QUEUED);
jobClient.countByStatus(JobStatus.RUNNING);
jobClient.countByStatus(JobStatus.RETRY_SCHEDULED);
jobClient.countByStatus(JobStatus.DLQ);
```

`QUEUED` includes delayed jobs, so a large `QUEUED` count is not automatically a backlog.
Compare `simplydone4j.jobs.claimed` against `submitted` to see whether work is moving.

### Working the DLQ

1. Inspect:

   ```java
   List<JobEntity> parked = deadLetterService.listDeadLettered(50);
   ```

2. Fix the cause: the handler, the payload producer, or the dependency.
3. Requeue a bounded batch. Requeueing grants a fresh attempt budget:

   ```java
   deadLetterService.requeueAll(50);
   ```

4. Confirm the DLQ drains. If the same jobs return, the cause is not fixed.

Do not requeue blindly after a downstream outage: a thousand jobs returning at once can
reproduce the outage. Requeue in batches and watch the queue.

### Draining before maintenance

The worker executor waits `executor.await-termination-seconds` (default 30) for in-flight
handlers on graceful shutdown. Stop the application first, then take Redis down. If you
must stop Redis while handlers are running, expect leases to expire and the reaper to
recover the jobs on the next start; make sure the handlers are idempotent.

### Scaling out

Every instance polls the same queues; no leader election is needed. Add instances to add
workers. Remember that `executor.core-pool-size` is per instance: total concurrency is
`instances × core-pool-size`.

### Rolling restarts

Restart instances one at a time. Jobs owned by a stopping instance either finish during the
drain or are reaped after their lease expires. No coordination is required, but expect a
brief bump in `lease.reaped` during a deploy.

### Upgrades

The 3.x line is wire-compatible with itself. Check the release notes for a version that
changes the Redis layout before deploying; a layout change needs a drain or a key-prefix
cutover, not a rolling restart.

## Incident playbooks

### Backlog is growing

1. Check `simplydone4j.jobs.duration`: are handlers slow, or are there simply not enough
   workers?
2. Check `simplydone4j.scheduler.claims.per.poll`: near zero with a large queue means the
   pool is saturated or the scheduler is stalled.
3. Check `lease.reaped` and `fencing.rejections`: duplicate work wastes capacity.
4. Scale instances, raise `executor.core-pool-size`, or shed load at the producer.
5. If the producer cannot be slowed, let `queue.max-depth` reject submissions; a caller
   that retries later is better than a backlog that never drains.

### Jobs stuck in `QUEUED`

A queued job with no queue entry is repaired by the reconciler within one
`worker.lease-reaper-interval-ms`. If it stays stuck:

- confirm the scheduler is enabled and the application is polling;
- check for a `key-prefix` change between the producer and the worker;
- inspect Redis directly for `<prefix>:job:<id>` and the queue ZSET member.

### Jobs stuck in `RUNNING`

A running job whose worker died is reaped after `worker.lease-timeout-seconds`. If it stays
`RUNNING` longer:

- confirm the reaper is running (same timer pool as the poll);
- check `simplydone4j.lease.reaped`;
- the lease may be getting renewed by a handler that is genuinely still running. Raise
  `lease-timeout-seconds` if the work legitimately takes that long.

### DLQ storm

1. Identify the dominant `errorCode` (`HANDLER_THREW`, `TIMEOUT`, `UNKNOWN_JOB_TYPE`,
   `PAYLOAD_INVALID`).
2. `UNKNOWN_JOB_TYPE` is a deployment bug: a handler was not registered before the
   scheduler started. Fix the registration, then requeue.
3. `PAYLOAD_INVALID` is a producer bug: the payload does not deserialize. Fix the producer;
   requeueing the same payload will fail again.
4. `HANDLER_THREW`/`TIMEOUT` usually means a downstream outage. Fix or wait, then requeue
   in batches.
5. Raise `retry.max-attempts` only if the failures are transient; a higher budget on a
   permanent failure just delays the DLQ.

### High `fencing.rejections`

Handlers are outliving their lease, so another worker takes over and the first worker's
write is discarded. That is correct behaviour, but it means duplicate work.

- Raise `worker.lease-timeout-seconds` above the slowest healthy handler.
- Look for a handler that ignores timeouts or blocks indefinitely.
- Check for a Redis latency spike that delays lease renewals.

### Redis is down

- Submissions fail and health reports `DOWN`.
- In-flight handlers that finish cannot record their outcome; their leases expire and the
  jobs are retried when Redis returns. Idempotency makes that safe.
- Do not flush Redis to "reset" the engine. Every job is in there.

## Capacity

Rough sizing:

- A job record is dominated by its payload and result. Estimate
  `(payload + result + ~1 KB overhead) × live jobs`.
- Terminal jobs live for `ttl-days` + `ttl-hours`. Shorten retention to bound memory.
- `retention.clear-payload-on-completion` trades auditability for footprint.
- Execution logs add one list entry per attempt, capped per job.
- Indexes carry the same retention as terminal jobs and are pruned with them.

Size Redis for the worst backlog, not the steady state, and set
`maxmemory-policy noeviction`. Eviction is data loss for a queue.

## Backup and restore

The queue is data. Back it up the way you back up Redis (RDB/AOF snapshots), and restore it
with the application stopped or the scheduler disabled (`simplydone4j.scheduler.enabled:
false`) so no worker consumes jobs mid-restore.

Restoring an older snapshot re-runs jobs that completed after it was taken. Idempotent
handlers make that a duplicate, not a corruption.
