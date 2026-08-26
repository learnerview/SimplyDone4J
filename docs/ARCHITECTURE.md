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
| `{p}:job:{id}` | HASH | JobEntity fields | Job record, TTL on terminal |
| `{p}:idx:status:{status}` | ZSET | `nextRunAt`/`visibleAt` | Secondary index for promoter/reaper |
| `{p}:idx:status:priority:{s}:{pr}` | ZSET | same | Per-priority index |
| `{p}:idempotency:{producer}:{key}` | STRING | jobId | SETNX dedup, TTL 1h |
| `{p}:ratelimit:{producer}` | ZSET | timestamps | Sliding window rate limit |
| `{p}:log:{id}` | LIST | JSON log entries | Execution logs, max 50, 7d TTL |

**Key insight:** Scoring queues by `scheduledAtEpochMs` gives free delayed execution — `ZRANGEBYSCORE 0..now` only returns due jobs. Same trick powers status indexes for the retry promoter (`RETRY_SCHEDULED`) and lease reaper (`RUNNING` past `visibleUntil`).

---

## Core Algorithms

### 1. Deficit Weighted Round-Robin (`SchedulerEngine.java:47-74`)

```java
deficit[i] += weights[i];              // every poll
pick highest deficit where queueSize > 0;
deficit[best] -= totalWeight;          // after claiming
```

- Weights: HIGH=70, NORMAL=20, LOW=10 (total 100)
- Guarantees no starvation — lower priorities accumulate deficit and eventually win

### 2. Exponential Backoff (`ExponentialBackoffRetryPolicy.java:17`)

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
CLOSED →(5 failures OR slow call >2s)→ OPEN →(30s)→ HALF_OPEN →(probe success)→ CLOSED
                                                         (probe failure)→ OPEN
```

- Thread-safe: `AtomicInteger` counter + `volatile` state/time
- `isOpen()` lazily performs OPEN→HALF_OPEN transition

### 5. Optimistic Locking (WATCH/MULTI/EXEC)

Two implementations:

**Queue claim (`RedisQueueRepository.java:37-60`):**
```
WATCH queueKey
ZRANGEBYSCORE(0, now, 0, 1)  -- fetch lowest-scored due job
MULTI ZREM jobId
EXEC  → empty result = race lost, return empty
```

**Job claim (`RedisJobRepository.java:136-200`):**
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

- UUID token generated at claim time, stored with job
- Before writing SUCCESS/failure, executor re-fetches and compares `leaseToken` (`JobExecutorServiceImpl.java:123-126`)
- Mismatch ⇒ another worker owns it ⇒ skip write (prevents zombie-worker double-completion)

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
CompletableFuture.supplyAsync(() -> handler.handle(ctx), executor)
        .get(timeoutSeconds, SECONDS);
```

Note: a timed-out handler is **not interrupted** — it keeps running in the pool. This is a known limitation (see [Known Limitations](#known-limitations)).

---

## Threading Model

| Component | Pool | Queue | Policy |
|---|---|---|---|
| Job execution | `ThreadPoolTaskExecutor` (core=4, max=8, queue=100) | Bounded (100) | CallerRunsPolicy |
| Scheduler | `@Scheduled` (single-thread) | — | — |
| Maintenance | `@Scheduled` (single-thread each) | — | — |

Graceful shutdown: `waitForTasksToCompleteOnShutdown=true`, `awaitTermination=30s`.