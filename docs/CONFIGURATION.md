# Configuration Reference

All properties are under the `simplydone4j.*` prefix and support Spring Boot's relaxed binding (kebab-case in YAML, environment variables with `SIMPLYDONE4J_` prefix, command-line arguments with `--`).

## Complete Property Reference

### Core

| Property | Default | Description |
|---|---|---|
| `scheduler.enabled` | `true` | Enable scheduler + worker maintenance. Set `false` for submission-only nodes |
| `scheduler.polling-interval-ms` | `1000` | How often the scheduler polls queues when idle |
| `scheduler.busy-polling-interval-ms` | `25` | How often the scheduler re-polls while it still holds permits |
| `scheduler.max-claim-batch` | `256` | Maximum number of queue ids claimed in one poll |
| `scheduler.queue-prefix` | `<key-prefix>:queue` | Redis key prefix for priority queues |
| `scheduler.weights.high` | `70` | Scheduling weight for HIGH priority |
| `scheduler.weights.normal` | `20` | Scheduling weight for NORMAL priority |
| `scheduler.weights.low` | `10` | Scheduling weight for LOW priority |

### Retry

| Property | Default | Description |
|---|---|---|
| `retry.max-attempts` | `3` | Max attempts (including initial try) |
| `retry.initial-delay-seconds` | `5` | Delay before first retry |
| `retry.backoff-multiplier` | `2.0` | Exponential backoff multiplier |
| `retry.jitter-factor` | `0.1` | Random spread around each retry delay, as a fraction of the base delay. `0` disables jitter; `1.0` allows +/-100%. |

### Worker

| Property | Default | Description |
|---|---|---|
| `worker.lease-timeout-seconds` | `30` | Max lease hold time per worker |
| `worker.maintenance-pool-size` | `3` | Threads reserved for retry promotion, lease recovery, and orphan reconciliation. A blocked task does not stall the others. |
| `worker.retry-promoter-interval-ms` | `1000` | Frequency of retry promotion |
| `worker.retry-promoter-scan-limit` | `100` | Maximum due retry records inspected per promotion pass |
| `worker.lease-reaper-interval-ms` | `5000` | Frequency of lease recovery |
| `worker.lease-reaper-scan-limit` | `100` | Maximum expired leases inspected per recovery pass |
| `worker.orphan-reconcile-interval-ms` | `15000` | Frequency of orphaned `QUEUED` job reconciliation |
| `worker.orphan-reconcile-scan-limit` | `200` | Maximum orphaned `QUEUED` jobs inspected per pass |
| `worker.orphan-grace-ms` | `5000` | Minimum age before a `QUEUED` job is treated as orphaned, protecting in-flight submissions |

### Queue

| Property | Default | Description |
|---|---|---|
| `queue.max-depth` | `10000` | Admission cap for submissions and scheduled retries across all priority queues. Claim restoration may briefly exceed it by the in-flight worker count. |

### Submission Resolution

| Property | Default | Description |
|---|---|---|
| `submission.reservation-record-wait-ms` | `1000` | Max wait for a reservation owner's job record to become visible |
| `submission.admission-wait-ms` | `1000` | Max wait for a duplicate's queue admission to become visible before returning a retryable conflict |
| `submission.resolve-poll-interval-ms` | `25` | Poll interval while resolving an in-flight duplicate |
| `submission.max-payload-bytes` | `1048576` | Maximum serialized payload size in UTF-8 bytes. `0` disables the limit. |

### Executor (Thread Pool)

| Property | Default | Description |
|---|---|---|
| `executor.core-pool-size` | `4` | Worker thread pool core size |
| `executor.max-pool-size` | `8` | Worker thread pool max size |
| `executor.queue-capacity` | `100` | Ignored by auto-configuration; kept for binding compatibility. Dispatch uses direct handoff so a claimed task never waits in the executor queue. Changing this property has no effect on the auto-configured executor. |
| `executor.keep-alive-seconds` | `60` | Idle thread keep-alive |
| `executor.default-timeout-seconds` | `30` | Default handler execution timeout |
| `executor.await-termination-seconds` | `30` | Graceful shutdown wait |

### Global

| Property | Default | Description |
|---|---|---|
| `key-prefix` | `simplydone4j` | Global Redis key prefix |
| `ttl-days` | `0` | Days component of finished-job data TTL |
| `ttl-hours` | `1` | Hours component of finished-job data TTL (effective TTL = `max(1, ttl-days * 24 + ttl-hours)` hours, default 1 hour) |
| `idempotency-ttl-hours` | `1` | TTL for idempotency locks |

### Retention

| Property | Default | Description |
|---|---|---|
| `retention.clear-payload-on-completion` | `false` | Clear job payload when a job reaches any terminal status (SUCCESS/FAILED/DLQ/CANCELLED) |
| `retention.store-execution-logs` | `true` | Store per-attempt execution logs in Redis |
| `retention.max-execution-logs-per-job` | `50` | Maximum log entries retained per job |
| `retention.execution-log-ttl-days` | `7` | TTL for per-attempt execution logs |

### Redis HA (Sentinel / Cluster)

| Property | Default | Description |
|---|---|---|
| `redis.sentinel-master` | — | Redis Sentinel master name (requires `redis.sentinel-nodes`) |
| `redis.sentinel-nodes` | — | Sentinel or Cluster nodes as `host:port` list |
| `redis.cluster-mode` | `false` | Treat `redis.sentinel-nodes` as a Redis Cluster instead of Sentinel |
| `redis.password` | — | Password for authenticated Sentinel/Cluster connections |
| `redis.pool-max-total` | `64` | Maximum pooled Lettuce connections for Sentinel/Cluster, including dedicated `WATCH/MULTI` connections |
| `redis.pool-max-idle` | `16` | Maximum idle pooled Lettuce connections |
| `redis.pool-min-idle` | `4` | Minimum idle pooled Lettuce connections |
| `redis.pool-max-wait-ms` | `2000` | Maximum wait for a pooled connection before failing |
| `redis.command-timeout-ms` | `5000` | Timeout for Sentinel/Cluster commands; bounds health checks and submissions when Redis is unreachable |

---

## Serialization

SimplyDone4J uses a dedicated internal `ObjectMapper` named
`simplyDoneObjectMapper` for Redis job hashes and execution logs. It does not reuse the
application's primary `ObjectMapper`, so an application-wide naming strategy, date
format, or custom module cannot change the stored Redis field names or make existing jobs
unreadable.

If you need to customize the internal mapper, declare a bean with that exact name:

```java
@Bean("simplyDoneObjectMapper")
ObjectMapper simplyDoneObjectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
}
```

Keep the mapper compatible with the persisted `JobEntity` and `JobExecutionLog` shapes.
Changing field names after jobs exist makes those records unreadable.

---

## Custom Example

```yaml
simplydone4j:
  scheduler:
    polling-interval-ms: 500
    weights:
      high: 80
      normal: 15
      low: 5
  retry:
    max-attempts: 5
    initial-delay-seconds: 2
    backoff-multiplier: 3.0
  executor:
    core-pool-size: 8
    max-pool-size: 16
    await-termination-seconds: 60
  ttl-days: 7
  idempotency-ttl-hours: 24
  retention:
    clear-payload-on-completion: true
    max-execution-logs-per-job: 100
  redis:
    sentinel-master: mymaster
    sentinel-nodes:
      - host1:26379
      - host2:26379
    password: mypassword   # optional
    pool-max-total: 64
    pool-max-idle: 16
    pool-min-idle: 4
    command-timeout-ms: 5000
```

---

## Environment Variables & Relaxed Binding

All properties support Spring Boot's relaxed binding. Common equivalences:

| YAML (kebab-case) | Environment Variable | CLI Argument |
|---|---|---|
| `simplydone4j.scheduler.enabled` | `SIMPLYDONE4J_SCHEDULER_ENABLED` | `--simplydone4j.scheduler.enabled=false` |
| `simplydone4j.retry.max-attempts` | `SIMPLYDONE4J_RETRY_MAX_ATTEMPTS` | `--simplydone4j.retry.max-attempts=5` |
| `simplydone4j.redis.sentinel-master` | `SIMPLYDONE4J_REDIS_SENTINEL_MASTER` | `--simplydone4j.redis.sentinel-master=mymaster` |
| `simplydone4j.redis.sentinel-nodes[0]` | `SIMPLYDONE4J_REDIS_SENTINEL_NODES[0]` | — |
| `simplydone4j.redis.password` | `SIMPLYDONE4J_REDIS_PASSWORD` | — |

Array/list properties (`redis.sentinel-nodes`) accept comma-separated values in env vars:
```bash
SIMPLYDONE4J_REDIS_SENTINEL_NODES=host1:26379,host2:26379,host3:26379
```

---

## Redis Connection

### Standalone (default)

No `simplydone4j.redis.*` required. Configure via Spring Boot's native properties:

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379
      # or with auth:
      # url: redis://:mypassword@localhost:6379
```

All `spring.data.redis.*` properties (timeout, database index, etc.) are honoured.

### Sentinel

```yaml
simplydone4j:
  redis:
    sentinel-master: mymaster
    sentinel-nodes:
      - host1:26379
      - host2:26379
    password: mypassword   # optional
```

Sentinel takes precedence over `spring.data.redis.*` when both are present. `spring.data.redis.*` extras (timeouts, database index) are not applied to the Sentinel factory.

### Cluster

```yaml
simplydone4j:
  redis:
    cluster-mode: true
    sentinel-nodes:
      - host1:6379
      - host2:6379
```

Set `cluster-mode: true` to treat the node list as a Redis Cluster rather than Sentinel.
Use a hash-tagged `key-prefix`, such as `simplydone4j:{jobs}`, because queue and
status scripts access multiple keys atomically.

### Connection pooling

`WATCH/MULTI` transactions use a dedicated Lettuce connection. Under sustained load,
opening a new TCP connection for every scheduler claim and lease write can exhaust the
client's ephemeral port range. `commons-pool2` is therefore a runtime dependency, and
Sentinel/Cluster factories always use a pooled Lettuce client. Tune
`redis.pool-max-total`, `redis.pool-max-idle`, and `redis.pool-min-idle` for the
expected number of concurrent submissions, claims, and lease renewals per application
instance. `redis.command-timeout-ms` bounds Redis operations so health checks fail fast
during an outage. Standalone connections are created by Spring Boot; set their command
timeout with `spring.data.redis.timeout` and their pool with the standard
`spring.data.redis.lettuce.pool.*` properties.

---

## Multi-tenancy

Isolate environments sharing the same Redis with `key-prefix`:

```yaml
# prod environment
simplydone4j:
  key-prefix: "prod"

# staging
simplydone4j:
  key-prefix: "staging"
```

All Redis keys are prefixed (`prod:job:...`, `staging:queue:high`, etc.).

---

## Data Retention

Retention differs by data type:

| Data | Redis key | Retention |
|---|---|---|
| Job record (terminal: SUCCESS/FAILED/DLQ/CANCELLED) | `{prefix}:job:<id>` | TTL of `max(1, ttl-days * 24 + ttl-hours)` hours (default 1 hour), set at terminal transition |
| Execution logs | `{prefix}:log:<id>` | `retention.execution-log-ttl-days` (default 7), max 50 entries per job (`retention.max-execution-logs-per-job`) |
| Status index entries for finished jobs | `{prefix}:idx:status:*` | Retained with the terminal retention window |
| Idempotency locks | `{prefix}:idempotency:*` | `idempotency-ttl-hours` (default 1) |

**Note:** Jobs stuck in a non-terminal state (`QUEUED`/`RUNNING`/`RETRY_SCHEDULED`) carry no TTL — they are retained until they reach a terminal state or are cancelled. The lease reaper and retry promoter exist precisely to drive stuck jobs toward termination.

```yaml
simplydone4j:
  ttl-days: 90   # 90-day TTL for finished jobs
```

---

## Operational checks

`EngineStatusService` is always auto-configured and adds no dependencies. It performs a
Redis `PING` and reports local loop state, worker capacity, and queue depths:

```java
EngineStatus status = engineStatusService.status();
boolean ready = status.healthy();
```

`healthy()` requires Redis to be reachable and, unless
`simplydone4j.scheduler.enabled=false`, both the scheduler loop and the worker-maintenance
loop to be running. Queue depth is informational and does not fail the check. Redis failures
are reported as unhealthy instead of being thrown into the caller.

The library does not auto-configure Actuator endpoints or Micrometer meters. Expose this
status from your application's own health endpoint or scheduler if you need probes or
metrics. No extra property is required.

### Dead-letter recovery

`DeadLetterService` is always auto-configured. There are no DLQ-specific properties;
`replay` reuses `queue.max-depth` for admission and `retry.max-attempts` when a record has
no positive `maxAttempts`.

---

## Deployment checklist

- Set `spring.data.redis.timeout` for standalone Redis and `redis.command-timeout-ms` for Sentinel/Cluster so failed commands and health probes do not wait on TCP timeouts.
- Use Redis Sentinel, managed Redis, or a tested failover plan for the hard dependency.
- Decide whether acknowledged writes must survive failover. If they must, configure Redis `min-replicas-to-write` and `min-replicas-max-lag`; expect writes to fail while the replica count is below the threshold.
- Unique `key-prefix` per environment; hash-tagged prefix for Redis Cluster.
- `queue.max-depth` sized for your accept/backpressure policy.
- `worker.lease-timeout-seconds` above your slowest expected handler, with cooperative
  timeout handlers.
- Idempotent handlers for every externally visible side effect (at-least-once delivery).
- Retention window (`ttl-days` + `ttl-hours`) aligned with your DLQ inspection policy.
- A scheduled caller or endpoint for `EngineStatusService` that alerts when `healthy()`
  becomes false.
- An explicit DLQ policy: who reviews `DeadLetterService.list`, when to `replay`, and when
  to `discard`.
- Restrict network access to Redis; use authentication and TLS where the deployment requires it. The Sentinel/Cluster factory accepts `redis.password` but does not add TLS automatically.