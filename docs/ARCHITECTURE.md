# Architecture

SimplyDone4J is an embedded engine, not a service. It runs inside your Spring Boot
application, uses Redis as its only durable store, and adds no daemon, database or worker
process.

This document explains the moving parts, the guarantees each one provides, and why the
boundaries are drawn where they are. If you only want to use the library, start with
the [README](../README.md).

## Design principles

The engine is deliberately small. The rules that shaped it:

- **One durable dependency.** Redis is the queue, the job store, the idempotency store and
  the lock service. There is no second system to keep consistent.
- **One recorded outcome per attempt.** A claim carries a fencing token; every terminal
  write is conditional on still holding it. A worker that lost its lease cannot overwrite
  the attempt that replaced it.
- **The store is the source of truth.** In-memory state (the DRR deficit, the worker pool)
  is an optimization. Losing a process loses no job that Redis acknowledged.
- **Narrow interfaces at the seams.** The engine depends on `JobRepository`,
  `QueueRepository`, `RetryPolicy`, `UniquenessGuard`, `JobMetrics` and
  the `api/` contracts, not on their implementations. Any of them can be replaced without
  touching the engine.
- **No hidden feature switches.** The library does not ship a rate limiter, a scheduler
  DSL, job chaining or a second persistence backend. Applications own admission policy;
  the queue owns backpressure.

## Component map

```
+------------------------------------------------------+
| application                                          |
|   api/  JobClient, JobSubmission, JobView            |
+---------------------------+--------------------------+
                            |
+---------------------------v--------------------------+
| service/                                             |
|   submission, retry, dead letter                     |
|   scheduler, executor, maintenance                   |
+---------------------------+--------------------------+
              +-------------+-------------+
              |                           |
+-------------v------------+   +----------v-----------+
| repository/              |   | handler/             |
|   job, queue,            |   |   JobHandler         |
|   idempotency, fencing,  |   |   JobContext         |
|   uniqueness             |   |   HandlerRegistry    |
+-------------+------------+   +----------------------+
              |
+-------------v------------+
| Redis                    |
|   hashes, ZSETs, indexes |
+--------------------------+
```

| Package | Responsibility |
| --- | --- |
| `api/` | Public consumer surface. `JobClient` is the entry point; `JobView` is the read model; `api.spi` holds replaceable policies. |
| `autoconfigure/` | Wires defaults, binds `simplydone4j.*`, starts the loop and periodic work. Every bean is `@ConditionalOnMissingBean`. |
| `service/` | Use cases: submit, schedule, execute, retry, dead-letter recovery, maintenance. |
| `repository/` | Redis persistence. Interfaces live beside their Redis implementations. |
| `handler/` | The handler contract and registry. |
| `event/` | Lifecycle events published through Spring's `ApplicationEventPublisher`. |
| `metrics/`, `health/` | Optional observability. Both degrade to no-ops when their dependency is absent. |

## Submission

1. `JobClient.submit` validates the `JobSubmission` and converts the payload to a map with
   the configured `PayloadCodec`.
2. The internal `JobSubmissionService` reserves the key in the idempotency store as
   `pending:<jobId>` with a 60-second TTL. Concurrent duplicate submissions are told to
   retry shortly (`JobSubmissionInProgressException`) or adopt the job if already enqueued;
   a duplicate is never handed a handle to an unadmitted job that could still be rolled back.
3. The configured `AdmissionPolicy` decides whether to proceed. The default admits
   everything. A rejection raises `AdmissionRejectedException`.
4. The job entity is written to Redis via an atomic Lua script (`RedisJobScripts.SAVE`).
5. A job that is due now is added to its priority queue with the due time as the score
   via capacity-checked admission (`queue.max-depth`). If capacity is exceeded or enqueue
   fails, compensation deletes the job and releases the idempotency reservation.
6. Once enqueued, the idempotency reservation is committed to `<jobId>` with the full
   retention TTL.
7. `JOB_CREATED` is published, which wakes the scheduler. A retry promotion or a DLQ
   requeue publishes `JOB_QUEUED`, which wakes it the same way.

A delayed job is stored as `QUEUED` with a future `nextRunAt` and a future queue score.
There is no separate `SCHEDULED` status: a job becomes claimable when its score is due.

## Scheduling

Each priority (`HIGH`, `NORMAL`, `LOW`) is a Redis sorted set scored by due time. The
scheduler uses **per-job weighted deficit round-robin** rather than strict priority:

- Quotas are allocated per job slot based on configured weights (`70/20/10` by default).
- For every available slot up to the claim limit, weights are added to the deficit and the
  queue with the highest deficit is allocated the slot.
- If a queue drains between depth sampling and batch claim, the unserved slot quota is
  refunded to its deficit so the queue is not penalized for work it could not supply.
- The claim limit is bounded by worker thread capacity, ensuring jobs are only claimed
  when workers are actually available to start them immediately.

`claimReady` removes ids from the sorted set with atomic Lua/ZPOPMIN semantics so two
instances cannot claim the same member. The scheduler then transitions each job
`QUEUED → RUNNING` with a monotonic fencing token and a lease deadline. If a claim fails
or races with cancellation, a job that remains `QUEUED` is safely restored to its queue.
[queue reconciler](#crash-recovery) exists.

## Execution

`JobExecutorService` runs the handler on the worker pool and owns four rules:

| Concern | Behaviour |
| --- | --- |
| Timeout | A watchdog records `TIMEOUT` if the handler exceeds its budget. The handler is not interrupted; it keeps its thread until it returns. |
| Cancellation | A once-per-second poll raises the flag visible through `JobContext.isCancellationRequested()`. Cancellation is cooperative. |
| Lease renewal | The lease is extended every third of `lease-timeout-seconds` while the handler runs, so a slow but healthy handler is not reaped. |
| Uniqueness | If `uniqueKey` is set and the guard is enabled, the job acquires a cluster-wide lock or is deferred. |

Exactly one outcome is recorded. The watchdog and the handler race through
`ScheduledFuture.cancel(false)`: whichever wins writes, and the loser's write is fenced
out at the store. This is what makes "one recorded result" true even when both paths run.

## Retries and the dead-letter queue

A failed attempt either schedules a retry or moves the job to `DLQ`:

- `attemptCount` starts at `0` for the first attempt.
- With `maxAttempts = 3`, the job gets three attempts in total.
- `RetryPolicy` computes the delay. The default is exponential with jitter.
- `UNKNOWN_JOB_TYPE` and `PAYLOAD_INVALID` are terminal: retrying cannot fix them, so the
  job goes to `FAILED` without consuming the attempt budget.
- Exhausting the budget moves the job to `DLQ`. Recovery is explicit through
  `DeadLetterService`, which grants a fresh budget.

## Crash recovery

Three periodic tasks close the windows a crash can open:

| Task | Interval | What it repairs |
| --- | --- | --- |
| Retry promoter | `retry-promoter-interval-ms` | Moves `RETRY_SCHEDULED` jobs whose delay has elapsed back to `QUEUED`. |
| Lease reaper | `lease-reaper-interval-ms` | Fails `RUNNING` jobs whose lease expired, so they retry or dead-letter. |
| Queue reconciler | `lease-reaper-interval-ms` | Re-enqueues `QUEUED` jobs that have no queue entry. This is the recovery path for the claim/transition gap. |

The reconciler is bounded per pass (200 jobs) and idempotent: it only adds a member that is
missing, using the job's original due time. A future-scheduled job is therefore restored
without becoming runnable early.

## Redis data model

All keys derive from `simplydone4j.key-prefix` (default `simplydone4j`):

| Key | Type | Purpose |
| --- | --- | --- |
| `<prefix>:job:<id>` | hash | The job record: status, payload, attempts, lease, result. |
| `<prefix>:queue:<priority>` | ZSET | Ready jobs, scored by due time. |
| `<prefix>:idx:status:<status>` | ZSET | Jobs by status, scored by update time. |
| `<prefix>:idx:status:priority:<status>:<priority>` | ZSET | Status queries narrowed by priority. |
| `<prefix>:idempotency:<producer>:<key>` | string | Idempotency ticket. |
| `<prefix>:unique:<key>` | string | Uniqueness lock. |
| `<prefix>:fencing:seq` | string | Monotonic fencing-token counter. |
| `<prefix>:log:<id>` | list | Optional per-attempt execution log. |

Terminal jobs are retained for `ttl-days` + `ttl-hours` (floored at one hour). Payloads can
be cleared on completion with `retention.clear-payload-on-completion`.

## Atomicity

These operations are atomic **because Redis executes them as one unit**:

- capacity-checked enqueue (Lua),
- claim (`WATCH`/`MULTI`),
- cancellation (`compare-and-set` on status),
- fenced terminal write (`compare-and-set` on lease token).

The guarantees are Redis-specific. A replacement `JobRepository` or `QueueRepository`
must provide equivalent atomicity, not merely the same method signatures.

## Why not Redis Cluster?

The fenced write is one script over the job hash and its status/priority indexes. A Redis
Cluster only runs a script when every key it touches shares a hash slot, and the job's
index keys do not share a slot by construction. Standalone is the primary target; Sentinel
is supported for failover. The starter does not manage topology or resharding.

## Extension points

The primary extension points live in the `api/` and `service/` SPI layers:
- `PayloadCodec`: Customize JSON / binary serialization (Jackson is default).
- `AdmissionPolicy`: Customize cluster load shedding and admission rules.
- `RetryPolicy`: Customize backoff algorithms (exponential with jitter is default).
- `HandlerRegistry`: Register type-safe handlers dynamically or via Spring beans.
