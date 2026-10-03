# Architecture

## Overview

```
┌─────────────────┐      ┌──────────────────┐      ┌─────────────────┐
│  Application    │      │  JobSubmission   │      │    Redis        │
│  (Producer)     │─────▶│  Service         │─────▶│  (Queue + Hash) │
└─────────────────┘      └────────┬─────────┘      └─────────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    ▼                           ▼
            ┌───────────────┐            ┌──────────────┐
            │ Scheduler     │            │ JobExecutor  │
            │ (Deficit WRR) │            │ (Lease +     │
            │               │            │  Fencing)    │
            └───────┬───────┘            └──────┬───────┘
                    │                           │
                    ▼                           ▼
            ┌───────────────┐            ┌──────────────┐
            │ Worker        │            │ Retry / DLQ  │
            │ Maintenance   │            │ Service      │
            └───────────────┘            └──────────────┘
```

---

## Redis Data Model

| Key | Type | Score / Content | Purpose |
|---|---|---|---|
| `{p}:queue:{priority}` | ZSET | `scheduledAtEpochMs` | Delayed-ready priority queues |
| `{p}:job:{id}` | HASH | JobEntity fields | Job record, TTL on terminal (`ttl-hours` + `ttl-days`) |
| `{p}:idx:status:{status}` | ZSET | `nextRunAt`/`visibleAt`; terminal members scored `updatedAt` | Secondary index for promoter/reaper, and for `countByStatus` on terminal states |
| `{p}:idx:status:priority:{s}:{pr}` | ZSET | same | Per-priority index |
| `{p}:idempotency:{producer}:{key}` | STRING | jobId | SETNX dedup, TTL `idempotency-ttl-hours` (1h) |
| `{p}:unique:{key}` | STRING | owner token | Overlap guard, TTL `uniqueness.ttl-seconds` or timeout + 30s |
| `{p}:fencing:seq` | STRING | counter | Monotonic fencing tokens via `INCR`. **Never expires** - a reset would restart the sequence and let a stale worker's token pass the fence |
| `{p}:ratelimit:{producer}` | ZSET | timestamps | Sliding window rate limit |
| `{p}:log:{id}` | LIST | JSON log entries | Execution logs, **opt-in** (`retention.store-execution-logs`, default off), trimmed to `retention.max-execution-logs-per-job`, TTL same as job |

**Key insight:** Scoring queues by `scheduledAtEpochMs` gives free delayed execution — `ZRANGEBYSCORE 0..now` only returns due jobs. Same trick powers status indexes for the retry promoter (`RETRY_SCHEDULED`) and lease reaper (`RUNNING` past `visibleUntil`).

---

## Core Algorithms

### 1. Deficit Weighted Round-Robin (`SchedulerEngine.java:64-74, 86-113`)

```java
deficit[i] += weights[i];              // every poll
pick highest deficit where queueSize > 0;
deficit[best] -= totalWeight;          // after claiming a batch
```

- Weights are relative (not percentages, need not sum to anything): HIGH=70, NORMAL=20, LOW=10
- Defaults to a batch of `batchSize` (default 10) jobs per poll
- Deficit is **not** reset when a queue drains — an idle queue accumulates credit, so a burst can immediately transmit instead of waiting a full round. This is what makes it deficit RR rather than plain weighted RR.

### 2. Exponential Backoff (`ExponentialBackoffRetryPolicy.java:22`)

```java
delayMs = initialDelaySeconds * 1000 * Math.pow(multiplier, attempt);
```

Defaults: 5s × 2.0^attempt → 5s, 10s, then DLQ (maxAttempts=3)

### 3. Sliding Window Rate Limiter (`scripts/rate_limit.lua`)

```lua
-- Atomic in Redis via Lua
ZREMRANGEBYSCORE(key, 0, now-windowMs)  -- purge expired
count = ZCARD(key)
if count >= max then return {0, oldestScore}
ZADD(key, now, now)
return {1, oldest}
```

- Atomic check-and-set via Lua (single Redis round-trip)
- `retryAfter = oldest + windowMs - now (+1s buffer)`
- Key TTL = 2× windowSeconds (self-cleaning)

### 4. Circuit Breaker (`RateLimiterCircuitBreaker.java`)

```
CLOSED →(5 infrastructure failures)→ OPEN →(30s)→ HALF_OPEN →(probe success)→ CLOSED
                                                             (probe failure)→ OPEN
```

- Counts **infrastructure** failures only (Redis exception, malformed script response). A rate-limit rejection is a legitimate business result and never trips the circuit.
- Thread-safe: `AtomicInteger` failure counter + `AtomicReference<State>` + `AtomicLong` last-failure timestamp
- `isOpen()` lazily performs OPEN→HALF_OPEN transition via CAS, so exactly one probing request gets through

### 5. Optimistic Locking (WATCH/MULTI/EXEC)

Two implementations:

**Queue claim (`RedisQueueRepository.java:46-79`):**
```
WATCH queue:priority
ZRANGEBYSCORE(0, now, 0, limit)   -- fetch up to `limit` due jobs (batch claim)
MULTI ZREM [jobId1, jobId2, ...]
EXEC  → null/empty result = race lost, return empty
```

Batch claiming matters for throughput: claiming one job per poll caps throughput at
`1000 / pollingIntervalMs` jobs per second per instance no matter the worker pool size.

**Job claim (`RedisJobRepository.java`):**
```
WATCH job:{id}
HGETALL + deserialize
if (status != fromStatus) return 0
mutate: status, leaseToken, leaseOwner, visibleAt, startedAt, updatedAt
MULTI
  ZREM from ALL status+priority indexes
  HPUT updated fields
  EXPIRE if terminal
  ZADD into toStatus index
EXEC → empty = race lost, return 0
```

### 6. Lease Fencing Tokens

- Monotonic token issued by `FencingTokenSequence` (Redis `INCR`) at claim time, not a UUID — a fencing token must be ordered, so that a downstream store can reject a late write from a superseded epoch.
- Before writing SUCCESS/failure, the executor captures the token it was issued and re-reads the job, then passes the *issued* token to `saveIfLeaseHeld` for an atomic compare-and-write (`JobExecutorServiceImpl`).
- Mismatch ⇒ another worker owns it ⇒ skip write (prevents zombie-worker double-completion). The lease reaper uses the same fenced path, so two instances observing the same expired lease cannot both increment the attempt count.

---

## Design Decisions

### Status vs Events (Noun vs Verb)

| | `JobStatus` | `JobEvent` |
|---|---|---|
| Question answered | Current state at rest | A transition that just fired |
| Persistence | Stored in Redis hash + drives ZSET indexes | Fire-and-forget, never stored |
| Cardinality | Exactly **one** per job | **Many** per job lifetime |
| Consumers | Internal machinery (indexes, maintenance) | External observers via `@EventListener` |

**Why separate?**
- Merge into status-only → observers must poll DB or hook repository internals
- Merge into events-only → scheduler has no queryable state
- Separate = **queries read status, reactions subscribe to events** (CQRS-lite)

### String Payload Rationale

`JobEntity.payload` is a `String` (serialized JSON) because:
1. Redis hashes store flat strings only — a `Map` field would serialize anyway
2. Entity maps 1:1 to hash via `objectMapper.convertValue(entity ↔ Map<String,String>)`
3. Serialize once at submission; repository hot paths just shuffle bytes
4. Mirrors Kafka/HTTP body transport — boundaries speak objects, storage speaks bytes

### ID Generation Split

| Entity | Generated In | Why |
|---|---|---|
| `JobEntity.id` | Service layer (`JobSubmissionServiceImpl`) | Caller needs ID immediately for response, enqueue, dedup |
| `JobExecutionLog.id` | Repository layer (`RedisJobExecutionLogRepository.save`) | Fire-and-forget audit data; no external dependency |

### CallerRunsPolicy Backpressure

Thread pool uses `CallerRunsPolicy` — when queue is full, the submitting thread executes the task. This provides natural backpressure without rejecting work or dropping jobs.

### Timeout Enforcement

```java
// runs the handler INLINE on the worker thread
executor.submit(() -> executeWithTimeout(job, timeoutSeconds));

// ... inside executeWithTimeout:
runHandler(job, handler, timeoutSeconds, start);   // blocks this thread
future = timeoutScheduler.schedule(watchdog, timeoutSeconds, SECONDS);
```

The handler runs on the calling worker thread and a watchdog on a separate scheduler
enforces the deadline. Exactly one path may record an outcome: `ScheduledFuture.cancel`
returns `false` once the watchdog has begun, so a handler that returns in time writes the
success, and one that does not is failed by the watchdog (the late success is dropped).

This is intentionally different from `future.get(timeout)` — submitting the handler back to
the same bounded pool and blocking on it self-deadlocks, because every worker thread waits
on a handler task queued behind those very threads.

Note: a timed-out handler is **not interrupted** — it keeps running on the worker thread.
This is a known limitation (see [Known Limitations](#known-limitations)).

---

## Threading Model

| Component | Pool | Queue | Policy |
|---|---|---|---|
| Job execution | `ThreadPoolTaskExecutor` (core=4, max=8, queue=100) | Bounded (100) | CallerRunsPolicy |
| Scheduler | Thread pool (1 by default; `spring.task.scheduling.pool.size` settable) | — | — |
| Maintenance | `@Scheduled` on a single scheduler thread each | — | — |

Graceful shutdown: `waitForTasksToCompleteOnShutdown=true`, `awaitTermination=30s`.