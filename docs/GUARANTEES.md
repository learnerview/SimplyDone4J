# Guarantees

This page states what SimplyDone4J promises, how each promise is enforced, and which test
proves it. It is deliberately narrower than "the job runs once": the engine provides
**at-least-once execution with at-most-one recorded outcome per attempt**. Handlers must
still be idempotent, because a worker can die after a side effect and before its outcome is
recorded.

The guarantee is only as strong as the store. Every claim below assumes a single Redis
primary (or Sentinel with the replication caveat noted at the end) and a repository that
provides the same atomicity as the Redis implementation.

## Guarantees

| # | Guarantee | Mechanism | Executable proof |
| --- | --- | --- | --- |
| G1 | A superseded worker cannot record success, failure or cancellation as the attempt’s outcome. | Terminal writes compare the lease token the attempt was issued against the token in Redis, in the same compare-and-write (WATCH/MULTI/EXEC) transaction that writes the record. | `LeaseFencingTest`, `RedisJobRepositoryWriteSemanticsRealRedisTest` |
| G2 | A superseded worker cannot renew a lease. | Lease renewal compares the token and extends the visibility deadline only if the stored lease still matches (`WATCH`/`MULTI`), keeping the status index member and deadline consistent. | `LeaseRenewerTest` |
| G4 | One queued job is claimed by at most one worker. | `claimReady` removes ids under `WATCH`/`MULTI`; `claimForExecution` transitions only from `QUEUED` under a second `WATCH`/`MULTI`. | `RedisQueueRepositoryTest`, `RedisQueueRepositoryWriteSemanticsRealRedisTest`, `SchedulerEngineTest` |
| G5 | Submission and scheduled-retry promotion admission never exceed `queue.max-depth`, even under concurrency. Claim restoration is an emergency safety path and may transiently exceed the cap by at most the number of in-flight workers. | The global depth check and `ZADD` run as one Lua script over all priority queues; retry promotion defers the job back to `RETRY_SCHEDULED` when the script reports a full queue. | `RedisQueueRepositoryWriteSemanticsRealRedisTest`, `WorkerMaintenanceServiceImplTest` |
| G8 | A retry is promoted by exactly one promoter. | `transitionStatusIfCurrent(RETRY_SCHEDULED, QUEUED)` is a compare-and-write; the loser does not enqueue. | `RetryPromotionRaceRealRedisTest`, `WorkerMaintenanceServiceImplTest` |
| G9 | A definite rejection or an unresolved ambiguous admission returns no success. | A definite rejection compensates by deleting the record and releasing the idempotency reservation. An ambiguous admission checks queue membership: a confirmed member is treated as accepted, while an unknown outcome leaves the `QUEUED` record for orphan reconciliation and throws, so the caller must treat the exception as outcome-unknown rather than proof that no job will run. A duplicate that observes a transient `QUEUED` record waits for admission or throws a retryable conflict. | `JobSubmissionServiceImplTest`, `RedisIdempotencyServiceRealRedisTest` |
| G10 | A duplicate submission with the same idempotency key resolves to one job, not a false success during admission. | Two-phase idempotency reservation (`pending:<jobId>` then `<jobId>`) with a compare-and-set; dedup of a `QUEUED` record requires observable queue membership or a later status. | `RedisIdempotencyServiceRealRedisTest`, `JobSubmissionServiceImplTest` |
| G11 | Cancellation cannot race a claim into an inconsistent state. | Cancellation compares `QUEUED` status and writes `CANCELLED` atomically; a concurrent claim wins or cancellation wins, never both. | `JobSubmissionServiceImplTest`, `RedisJobRepositoryWriteSemanticsRealRedisTest` |
| G12 | Exactly one outcome is recorded when the timeout watchdog and the handler finish together. | The worker records the single terminal outcome. If the watchdog wins the completion flag, the worker records a fenced timeout failure; if the handler wins, it records its own outcome. The store still rejects a superseded lease. | Partial: `LeaseFencingTest` covers competing terminal writes; `JobExecutorServiceImplTest` covers ordinary timeout/success and the watchdog/completion handoff. |
| G15 | Terminal jobs expire while terminal indexes are pruned. | Job hash and execution log carry retention (`retention.execution-log-ttl-days` defaults to 7 days); terminal index members are pruned by score on the read paths and the index key carries the same retention TTL. | `TerminalStatusIndexTest`, `RedisJobRepositoryWriteSemanticsRealRedisTest` |
| G16 | A `QUEUED` record left without a queue entry by a crashed claim is repaired automatically. | The orphan reconciler lists due `QUEUED` records, applies `worker.orphan-grace-ms`, checks membership with `QueueRepository.contains`, and re-admits through the capacity script. Errors are isolated per record. | `WorkerMaintenanceServiceImplTest` |
| G17 | A DLQ replay cannot produce a half-reset job or bypass queue capacity. | `saveIfStatus(DLQ -> QUEUED)` is a compare-and-write; queue admission uses the capacity-checked enqueue path. A rejected admission restores the `DLQ` record, and an ambiguous admission checks queue membership before declaring success. | `DeadLetterServiceImplTest`, `DeadLetterRealRedisTest` |
| G18 | A DLQ discard cannot delete a job that a concurrent replay has already returned to the queue. | Discard compares `DLQ -> FAILED` before deleting; the replay and discard transitions contend on the same persisted status, so only one wins. | `DeadLetterServiceImplTest`, `DeadLetterRealRedisTest` |
| G19 | An engine status check reports Redis failures instead of throwing them at the caller. | The status check catches `PING` and queue-read failures and returns `redisReachable=false` with no queue depths; local loop and capacity data remain available. | `EngineStatusServiceImplTest` |

Guarantees that are design goals but do not yet have a dedicated automated proof are marked as
such in the source-level design notes rather than being claimed as executable evidence.

## Deliberate non-guarantees

These are scope decisions, not defects. State them to users rather than letting them infer
a stronger promise:

- **Exactly-once side effects.** Execution is at-least-once. A worker can perform a side
  effect and die before its terminal write; the reaper then makes the job runnable again.
  Handlers that touch external systems must be idempotent or use an outbox.
- **Interrupting a running handler.** Timeout is cooperative. A handler that ignores
  thread interruption keeps its thread until it returns.
- **Replay side effects.** A replayed job runs at least once, including when its original
  attempt already performed an external side effect. Replay does not undo prior effects;
  handlers must be idempotent or use an outbox.
- **Automatic DLQ policy.** The engine never replays, discards, or retries a `DLQ` record on
  its own. Recovery requires an operator or application policy to call `DeadLetterService`.
- **Ordering across priorities.** Weighted deficit round-robin provides a share, not strict
  priority. A `LOW` job can run while `HIGH` work is backlogged once its deficit is due.
- **Bounded status-list reads.** `findByStatus(status)` and
  `findByProducerAndStatus(producer, status)` materialize every matching job. For large
  retained sets, use `findByStatus(status, limit)` or `countByStatus`; the producer query
  has no limited variant.
- **Bounded claim latency under contention.** `claimReady` retries optimistic transaction
  conflicts three times, then returns empty. Under sustained contention the scheduler can
  fall back to its idle polling interval, which adds latency but does not lose the job.
- **Exactly-once queue admission after a crash.** A process can die after `claimReady`
  removes an id and before `claimForExecution` commits. Reconciliation restores the job,
  but only after the next maintenance pass.
- **Cross-slot atomicity.** The atomic scripts touch several keys. Standalone Redis and
  Sentinel are supported. Redis Cluster works only when `key-prefix` contains a hash tag
  that maps every engine key to the same slot (for example
  `simplydone4j:{jobs}`); without that hash tag, Cluster rejects the scripts.
- **Unbounded token durability across failover.** `WAIT` is best-effort. A hard guarantee
  needs `min-replicas-to-write` and `min-replicas-max-lag` on the server.

## How to run the proof

The Redis-backed tests are skipped when no server is reachable, so the normal unit suite
still runs anywhere. To execute the full set:

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
mvn -B verify -Dit.redis.host=localhost -Dit.redis.port=6379
```

CI runs the Redis-backed tests against a Redis 7 service container. Treat a
guarantee as verified for a given environment only when its cited test runs
against that environment's Redis topology and configuration.

## High-concurrency load evidence

`LoadRealRedisIT` runs two application instances against one Redis, submits a
configurable number of jobs, consumes them, and checks duplicate/lost job counts and
final status totals. It is an integration/load test, not part of the default unit
suite:

```bash
mvn -B -Dtest=LoadRealRedisIT -Dload.jobs=10000 \
  -Dit.redis.host=localhost -Dit.redis.port=16379 test
```

One verified 10,000-job run with pooled Lettuce connections completed without
duplicate or lost jobs:

```text
LOAD OK jobs=10000 submit=9320ms total=31475ms submitRate=1073/s endToEnd=318/s
```

This is burst throughput evidence for one environment, not a soak test or a
universal capacity promise. Results vary with hardware, Redis topology, payload
size, and handler duration. The pool is required because `WATCH/MULTI`
transactions take dedicated connections; without it, sustained load can exhaust
the client's ephemeral port range. See [Verification](VERIFICATION.md) for the
full check list.
