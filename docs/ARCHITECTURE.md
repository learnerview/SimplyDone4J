# Architecture

This document describes the Redis data model, the concurrency algorithms, and the
failure-handling choices behind SimplyDone4J. It is written for people who need to
operate the engine, review it, or replace one of its components.

## Overview

```text
+------------------+      +------------------+      +------------------+
|  Application     |      |  JobSubmission   |      |     Redis        |
|  (Producer)      |----->|  Service         |----->|  queue + hashes  |
+------------------+      +------------------+      +------------------+
                                                          ^
                                                          |
                    +-------------------------------------+-----------------+
                    |                                       |                 |
            +------------------+                    +------------------+
            |    Scheduler     |                    |   JobExecutor    |
            |  (Deficit WRR)   |                    | (lease + fencing)|
            +------------------+                    +------------------+
                    |                                       |
            +------------------+                    +------------------+
            | Worker           |                    |  Retry / DLQ     |
            | Maintenance      |                    |  Service         |
            +------------------+                    +------------------+
```

There is no separate daemon and no database. Every application instance runs the
same embedded components: a submission service, one scheduler thread, a bounded
worker pool, and three maintenance tasks (retry promotion, lease recovery, and
orphan reconciliation).

## Redis Data Model

`{p}` is the configured `key-prefix` (default `simplydone4j`). `{q}` is
`scheduler.queue-prefix`, which defaults to `{p}:queue`. A custom
`scheduler.queue-prefix` is used verbatim and must therefore maintain isolation
the operator expects.

| Key | Type | Score / Content | Purpose |
|---|---|---|---|
| `{q}:high`, `{q}:normal`, `{q}:low` | ZSET | `scheduledAtEpochMs` | Priority queues; only members scored `<= now` are due |
| `{p}:job:{id}` | HASH | JobEntity fields | Source of truth for one job |
| `{p}:idx:status:{status}` | ZSET | `visibleAt` for RUNNING, `nextRunAt` for retries, completion time for terminal jobs | Index used by the retry promoter, lease reaper, and status queries |
| `{p}:idx:status:priority:{status}:{priority}` | ZSET | Same score as the status index | Per-priority status counts |
| `{p}:idempotency:{producer}:{key}` | STRING | `pending:{jobId}` or `{jobId}` | Two-phase idempotency reservation |
| `{p}:log:{id}` | LIST | JSON `JobExecutionLog` entries | Per-attempt audit trail |

Delayed execution is a consequence of the queue score: `ZRANGEBYSCORE 0..now`
returns only jobs whose scheduled time has arrived. The same trick drives the
retry promoter (`RETRY_SCHEDULED` members scored by `nextRunAt`) and the lease
reaper (`RUNNING` members scored by `visibleAt`).

## Public API and Internal Types

The supported application-facing surface is:

- `io.github.learnerview.simplydone4j.service.JobSubmissionService`
- `io.github.learnerview.simplydone4j.service.JobExecutorService`
- `io.github.learnerview.simplydone4j.service.SchedulerService`
- `io.github.learnerview.simplydone4j.service.WorkerMaintenanceService`
- `io.github.learnerview.simplydone4j.service.RetryPolicy`, `RetryService`, `JobSignal`,
  `WorkerCapacity`, and the idempotency interfaces
- `io.github.learnerview.simplydone4j.handler.JobHandler`, `JobContext`,
  `HandlerRegistry`
- `io.github.learnerview.simplydone4j.dto.*`, `entity.*`, `model.*`, `event.*`,
  and `exception.*`
- `io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties`

The `Redis*` repositories, `service.impl.*` classes, `SchedulerLoop`, and
`LeaseRenewer` are implementation types. They are public because the separate
auto-configuration package must instantiate them and because tests exercise them
directly; applications should depend on the interfaces above and override beans
only when they intentionally replace a component. Every auto-configured bean is
guarded by `@ConditionalOnMissingBean`.

## Submission Algorithm

`JobSubmissionServiceImpl.submit(producer, request)` runs these steps in order:

1. Validate the request. Validation is explicit so the service behaves the same
   when called directly or through a controller.
2. Read `job:{producer}:{idempotencyKey}`. A hit returns the existing job without
   charging admission capacity. If that job is still `QUEUED`, the duplicate waits
   briefly for its queue admission to become visible; it is not reported as accepted
   until the queue entry or a later status can be observed.
3. Atomically acquire an idempotency reservation with a Lua script:
   - existing value: return it;
   - otherwise `SET key pending:{newJobId} NX EX ttl`;
   - a lost `SET` race returns the winner's value.
4. If another owner's reservation exists:
   - wait up to `submission.reservation-record-wait-ms` for its job record (the
     owner may still be persisting);
   - if the record appears, return it;
   - if the reservation is still `pending`, reject the request as in flight
     rather than stealing the key;
   - if it is a confirmed reservation whose job has expired, release it
     compare-and-delete and retry acquisition once.
5. Persist the `QUEUED` job, then promote the reservation from `pending:{jobId}`
   to `{jobId}` under `WATCH`/`MULTI`, preserving the remaining TTL. Promotion
   failure is logged but not fatal: a duplicate can still resolve through the
   pending marker.
6. Admit the job with the atomic capacity script (below). On a full queue or a
   Redis error, compensate by deleting the record and releasing the reservation,
   then throw `QueueFullException` or the infrastructure exception.
7. Signal the scheduler and publish `JOB_CREATED`.

A duplicate can observe the `QUEUED` record after step 5 but before step 6 commits.
The resolver polls the queue membership for up to
`submission.admission-wait-ms` and returns the job only once admission is visible or
the job has moved past `QUEUED`. If admission is still
not observable, it throws a retryable conflict instead of returning a record that the
owner may be about to delete during queue-full compensation. This closes the window
where a duplicate could report success for a job that never entered the queue; an
orphaned record is still repaired by reconciliation.

The known crash window is between reservation acquisition and job persistence.
The reservation then stays pending until its TTL expires. This is documented in
the operational contract because avoiding it would require a distributed
transaction across the two writes.

## Atomic Queue Admission

`RedisQueueRepository.enqueueIfUnderCapacity` executes one Lua script. The script
reads `ZCARD` for all three priority queues, compares the sum with
`queue.max-depth`, and only then performs `ZADD` on the target queue. Redis runs
the script atomically, so two concurrent submissions cannot both observe the last
free slot.

Submissions and retry promotions use this path. Claim restoration is the
deliberate exception: it uses plain `ZADD` because returning a claimed job to the
queue is a safety action, not new work. The queue can therefore exceed
`max-depth` by at most the number of in-flight workers. This bound is stated in
the configuration reference and guarantees instead of being presented as strict
admission.

All scripts touch several keys. On Redis Cluster, use a hash-tagged prefix such
as `simplydone4j:{jobs}` so every key maps to one slot; standalone and Sentinel
do not require the tag.

## Scheduling Algorithm

`SchedulerEngine.pollOnce()` claims work in batches rather than one job per tick:

1. Read the number of idle worker permits. Return immediately when none are free.
2. Add each priority weight to its deficit counter.
3. Repeatedly select the priority with the largest deficit among queues that
   currently have ZSET members. If that priority has members but none are due,
   zero its deficit, exclude it for the rest of this poll, and select again. This
   prevents a HIGH queue holding only a future job from blocking NORMAL/LOW.
4. Claim up to `min(available, scheduler.max-claim-batch)` due ids with
   `RedisQueueRepository.claimReady`: `WATCH` the queue, fetch due members with
   scores, `MULTI`, remove exactly those members, `EXEC`. A conflicting `EXEC`
   returns no ids; the next poll retries.
5. For each claimed id, acquire a worker permit with `tryAcquire`. If the permit
   is gone, restore the job only when its stored status is still `QUEUED`.
6. Transition `QUEUED -> RUNNING` with a lease token, owner, and visibility
   deadline under a second `WATCH`/`MULTI` in `claimForExecution`. A failed
   transition restores the id only if it is still queued.
7. Hand the job to the executor. The permit is released by the executor when the
   handler finishes. If dispatch throws, the scheduler returns the job to the
   queue and releases the permit.
8. Subtract `totalWeight` from the selected priority's deficit for every
   dispatched job and signal the scheduler loop when at least one job started.

With weights 70/20/10, HIGH receives roughly 70% of dispatch opportunities,
NORMAL 20%, and LOW 10%, while every priority accumulates deficit and eventually
runs. This is a share, not strict priority: LOW work can run while HIGH work is
backlogged.

`SchedulerLoop` starts on `ApplicationReadyEvent`, calls `pollOnce()`, then waits
`busy-polling-interval-ms` if work was claimed or `polling-interval-ms` otherwise.
Signals are in-process semaphore releases. A signal can be lost on crash or
restart, so the timed wait is the loss-tolerant fallback rather than an
optimisation.

## Execution, Leases, and Fencing

Claiming a job creates a UUID lease token, stores it in the job hash, and sets
`visibleAt = now + lease-timeout-seconds`. The executor starts a renewal task
every `max(250ms, leaseTimeout / 3)`. `extendLease` compares the token under
`WATCH` and moves only the deadline; it does not change status or attempt count.

On completion or failure the executor writes the outcome with
`saveIfLeaseHeld`. Inside one `WATCH`/`MULTI` transaction the repository:

1. reads the persisted job;
2. rejects the write when the token is missing or different;
3. writes the new fields, deleting fields whose new value is null;
4. moves the id between status indexes;
5. applies or removes the retention TTL.

A superseded worker therefore cannot record an outcome. If the transaction is
aborted by a concurrent writer, the repository retries the compare-and-write up
to three times before giving up.

The timeout watchdog interrupts the handler thread. Interruption is cooperative:
a handler that ignores it keeps its thread, its renewal task, and its lease until
it returns. The outcome is still fenced, so a timeout does not become a hard kill
for arbitrary blocking code.

`WorkerCapacity` is a semaphore sized to `executor.max-pool-size`. It prevents the
scheduler from claiming more work than the executor can run and is released in the
executor's `finally` block, including timeout and failure paths.

## Retry and Dead-Letter Algorithm

`RetryServiceImpl.applyFailure` computes:

```text
willRetry = attemptCount + 1 < maxAttempts
baseDelayMs = initialDelaySeconds * 1000 * backoffMultiplier^attemptCount
delayMs     = random(baseDelayMs * (1 - jitterFactor),
                     baseDelayMs * (1 + jitterFactor))
```

- Retry: status becomes `RETRY_SCHEDULED`, `nextRunAt = now + delay`, lease fields
  and `visibleAt` are cleared, and `attemptCount` increments.
- Exhaustion: status becomes `DLQ`, the error is recorded, and the job gets a
  terminal timestamp. `FAILED` is a reserved status value; exhaustion uses `DLQ`.

The failure write is fenced with the attempt's lease token. Reaper-driven
failures additionally pass the observed `now` as `notAfter`; the repository
requires the persisted lease deadline to be at or before that instant. A renewer
that extends the lease between the reaper's listing and its write therefore wins,
and the reaper discards its stale failure.

With defaults (`maxAttempts=3`, 5s initial delay, multiplier 2.0, jitter 0.1),
the base delays are 5s before the first retry and 10s before the second, each
spread by up to 10%, then the job moves to DLQ. Set `retry.jitter-factor: 0` to
disable that spread.

`WorkerMaintenanceServiceImpl.promoteRetries()` lists up to
`worker.retry-promoter-scan-limit` due `RETRY_SCHEDULED` jobs and, for each:

1. compare-and-set `RETRY_SCHEDULED -> QUEUED`;
2. admit with the atomic capacity script;
3. if the queue is full, compare-and-set `QUEUED -> RETRY_SCHEDULED` and try again
   on the next promoter interval;
4. if the queue write throws, restore `RETRY_SCHEDULED` at once.

The status CAS is what prevents two promoters from both enqueueing the same retry,
or one promoter from overwriting a job another promoter's scheduler has already
claimed.

## Lease Reaper

`recoverExpiredLeases()` lists up to `worker.lease-reaper-scan-limit` `RUNNING`
jobs whose index score (`visibleAt`) is at or before `now`. For each id it re-reads the job, skips it if
it is no longer RUNNING, requires a lease token, and calls the overdue-fenced
failure path. The write succeeds only if the token still matches and the
persisted deadline has not moved past the observed `now`. The outcome is a normal
retry or DLQ transition, so recovery uses the same backoff and event flow as a
handler failure.

## Orphan Reconciliation

`reconcileOrphanedQueuedJobs()` lists up to
`worker.orphan-reconcile-scan-limit` `QUEUED` jobs whose `nextRunAt` is due. It
ignores records younger than `worker.orphan-grace-ms`, then checks queue membership
with `QueueRepository.contains`. A job with no queue entry is re-admitted through
the normal capacity script; if the queue is full it stays orphaned and is retried on
the next pass. Errors are isolated per job, so one bad record cannot abort the scan.
This repairs the crash window between `claimReady` removing an id and
`claimForExecution` committing `QUEUED -> RUNNING`.

## Event Flow

Lifecycle events are Spring `ApplicationEvent`s published after the corresponding
state transition:

| Event | Published after |
|---|---|
| `JOB_CREATED` | Job persisted, admitted, and signalled |
| `JOB_STARTED` | Job claimed and a dispatch to the executor is attempted |
| `JOB_COMPLETED` | Fenced success write |
| `JOB_RETRY` | Fenced retry transition |
| `JOB_FAILED` | Fenced transition to DLQ |
| `JOB_CANCELLED` | Fenced cancellation |
| `JOB_REPLAYED` | DLQ replay transition |
| `JOB_DISCARDED` | DLQ discard transition |

`JobEventPublisher.publish` catches runtime exceptions from synchronous listeners
and logs them. A listener must not be able to make a successful state transition
look like a failure to the caller, or to abort the scheduler thread.

## Cancellation

`cancelJob` applies only to `QUEUED` jobs, including delayed jobs. It uses
`transitionStatusIfCurrent(QUEUED, CANCELLED)`; if a worker claimed the job first,
the CAS loses and cancellation throws instead of overwriting a RUNNING job. On
success it removes the queue member and records the terminal fields. Running
handlers are never force-stopped; applications that need cancellation of running
work must implement a cooperative checkpoint in the handler, as ParcelFlow does.

## Retention and Index Consistency

- The terminal retention window is `max(1, ttl-days * 24 + ttl-hours)` hours. The
  floor prevents Redis `EXPIRE key 0`, which deletes a key rather than disabling
  expiry.
- Terminal job hashes and terminal status indexes receive that TTL.
- Non-terminal writes call `PERSIST`, so a requeued job cannot inherit an old
  terminal expiry and disappear while it is still queued.
- Execution logs are independent of job retention:
  `retention.execution-log-ttl-days` (default 7) and at most
  `retention.max-execution-logs-per-job` entries.
- Terminal index members are scored by completion time. Read paths prune members
  older than the retention window, which keeps status counts consistent without a
  background sweeper.
- `retention.clear-payload-on-completion` removes the payload from terminal job
  hashes. The default keeps it.

## Threading Model

| Component | Pool | Queue | Policy |
|---|---|---|---|
| Job execution | `ThreadPoolTaskExecutor` (core 4, max 8 by default) | Direct handoff (capacity 0) | `CallerRunsPolicy` |
| Scheduler | One dedicated daemon thread | none | Poll plus timed signal wait |
| Retry promoter | Maintenance scheduled pool (`worker.maintenance-pool-size`, default 3) | none | One task per interval |
| Lease reaper | Maintenance scheduled pool | none | One task per interval |
| Orphan reconciler | Maintenance scheduled pool | none | One task per interval |
| Lease renewal | Single scheduled executor, one task per running job | none | Fixed delay |
| Timeout watchdog | Same scheduled executor as renewal | none | One task per running job |

`CallerRunsPolicy` means a saturated executor can run a task on the scheduler
thread, providing backpressure without dropping work. Graceful shutdown waits for
tasks to complete (`waitForTasksToCompleteOnShutdown=true`) for up to
`executor.await-termination-seconds`.

## Failure Model

The engine provides at-least-once execution with at-most-one recorded outcome per
attempt. It does not promise exactly-once side effects.

- A process can die after removing a queue member and before the `QUEUED ->
  RUNNING` write. The job remains `QUEUED` but unqueued until the next orphan
  reconciliation pass restores it, bounded by
  `worker.orphan-reconcile-interval-ms` plus the grace period.
- A handler can perform an external side effect and die before the fenced write.
  The reaper runs the job again, so handlers must be idempotent or use an outbox.
- A signal can be lost because it is process-local. The polling fallback bounds
  the delay.
- Redis failover can lose an acknowledged write. Strong durability needs Redis
  `WAIT`, `min-replicas-to-write`, and `min-replicas-max-lag` on the server; the
  library does not turn a single-node guarantee into a replicated one.
