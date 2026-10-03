# Configuration Reference

All properties are under the `simplydone4j.*` prefix and support Spring Boot's relaxed binding (kebab-case in YAML, environment variables with `SIMPLYDONE4J_` prefix, command-line arguments with `--`).

Every property has a working default, so the starter runs with no configuration at all. The defaults suit a single node; the ones that matter once you scale out are called out below.

## Complete Property Reference

### Global

| Property | Default | Description |
|---|---|---|
| `key-prefix` | `simplydone4j` | Namespace for every Redis key the starter writes, including the priority queues and uniqueness locks. Change this to run two logically distinct deployments against one Redis instance. |
| `ttl-days` | `0` | Days component of finished-job data TTL |
| `ttl-hours` | `1` | Hours component. Effective TTL = `ttl-days×24 + ttl-hours` (default 1 hour) |
| `idempotency-ttl-hours` | `1` | How long an idempotency key is remembered. Set it above the longest client retry window |

### Scheduler

| Property | Default | Description |
|---|---|---|
| `scheduler.enabled` | `true` | Gate for the scheduler + worker maintenance beans. Set `false` for submission-only nodes |
| `scheduler.polling-interval-ms` | `1000` | How often the scheduler polls queues |
| `scheduler.queue-prefix` | *(blank)* | Explicit override for the queue key prefix. Leave blank to derive it from `key-prefix` (`<key-prefix>:queue`), so changing `key-prefix` moves the queues with it |
| `scheduler.batch-size` | `10` | Jobs claimed per poll, bounded by worker capacity. This is the throughput lever |
| `scheduler.weights.high` | `70` | Deficit round-robin weight for HIGH priority |
| `scheduler.weights.normal` | `20` | Weight for NORMAL priority |
| `scheduler.weights.low` | `10` | Weight for LOW priority |

### Rate Limiting

| Property | Default | Description |
|---|---|---|
| `rate-limit.requests-per-minute` | `60` | Max submissions per producer per `window-seconds` |
| `rate-limit.window-seconds` | `60` | Rate limit sliding window in seconds |
| `rate-limit.circuit-breaker-failures` | `5` | Consecutive infrastructure failures before the circuit opens and the in-memory strategy takes over |
| `rate-limit.circuit-breaker-reset-seconds` | `30` | Seconds before the open circuit allows a half-open probe |
| `rate-limit.slow-call-duration-ms` | `2000` | Reserved for API compatibility; the current circuit breaker counts infrastructure failures only and does not enforce this |

### Retry

| Property | Default | Description |
|---|---|---|
| `retry.max-attempts` | `3` | Attempt budget for a job that specifies none (including the initial try). Exhausting it moves the job to the DLQ |
| `retry.initial-delay-seconds` | `5` | Delay before the first retry |
| `retry.backoff-multiplier` | `2.0` | Exponential backoff multiplier |
| `retry.max-delay-seconds` | `300` | Upper bound on any single backoff |
| `retry.jitter-factor` | `0.2` | Fraction of the computed delay randomised in `[0,1]` to avoid lockstep retries |

### Worker

| Property | Default | Description |
|---|---|---|
| `worker.lease-timeout-seconds` | `30` | How long a claim is valid. A handler running longer is reaped and re-run elsewhere; the fencing token stops the original worker from overwriting the newer attempt |
| `worker.retry-promoter-interval-ms` | `1000` | Frequency of retry promotion |
| `worker.lease-reaper-interval-ms` | `5000` | Frequency of lease recovery |

### Queue

| Property | Default | Description |
|---|---|---|
| `queue.max-depth` | `10000` | Depth at which submission is refused with `QueueFullException` |

### Executor (Thread Pool)

| Property | Default | Description |
|---|---|---|
| `executor.core-pool-size` | `4` | Worker thread pool core size |
| `executor.max-pool-size` | `8` | Worker thread pool max size |
| `executor.queue-capacity` | `100` | Thread pool work queue capacity |
| `executor.keep-alive-seconds` | `60` | Idle thread keep-alive |
| `executor.default-timeout-seconds` | `30` | Default handler execution timeout |
| `executor.await-termination-seconds` | `30` | Graceful shutdown wait |

### Monitoring & Metrics

| Property | Default | Description |
|---|---|---|
| `monitoring.enabled` | `true` | Gate for the `MonitoringService` bean |
| `metrics.enabled` | `true` | Gate for Micrometer instrumentation. Off is the safe default for consumers without `micrometer-core`, which still get the no-op implementation |
| `metrics.queue-depth` | `true` | Expose the queue-depth and dead-letter gauges, sampled on a timer rather than the scrape path. Set `false` to skip the sampling scan |
| `metrics.queue-depth-refresh-seconds` | `30` | How often queue-depth gauges are refreshed |

### Health

| Property | Default | Description |
|---|---|---|
| `health.enabled` | `true` | Gate for the `HealthIndicator` bean |
| `health.dead-letter-threshold` | `1` | DLQ count at or above which health reports DOWN (`0` disables the check) |
| `health.max-queue-depth` | `100000` | **Total** depth across all priority queues above which health reports DOWN. `0` disables the check |

### Webhook

| Property | Default | Description |
|---|---|---|
| `webhook.enabled` | `true` | Master switch. Nothing is sent unless a job also carries a `callbackUrl`, so this is safe to leave on |
| `webhook.retry-enabled` | `true` | Turn off to collapse delivery to a single attempt |
| `webhook.max-attempts` | `3` | Total delivery attempts including the first |
| `webhook.connect-timeout-millis` | `2000` | TCP connect timeout |
| `webhook.request-timeout-millis` | `5000` | Per-request timeout |
| `webhook.initial-delay-millis` | `500` | Delay before the first retry |
| `webhook.backoff-multiplier` | `2.0` | Exponential backoff multiplier |
| `webhook.max-delay-millis` | `30000` | Upper bound on any single backoff |
| `webhook.jitter-factor` | `0.2` | Randomisation fraction to avoid lockstep delivery |

### Uniqueness

| Property | Default | Description |
|---|---|---|
| `uniqueness.enabled` | `false` | Cluster-wide mutual exclusion for jobs that submit a `uniqueKey`. Costs one Redis round trip per keyed job; off by default |
| `uniqueness.ttl-seconds` | `0` | How long to hold the key. Should exceed the job's execution timeout. `0` derives the TTL from the job timeout + 30s grace |
| `uniqueness.defer-seconds` | `5` | How long a job that found its key held is postponed before it is re-enqueued |

### Retention

| Property | Default | Description |
|---|---|---|
| `retention.clear-payload-on-completion` | `false` | Drop the payload once a job is terminal (result and error text are kept either way) |
| `retention.store-execution-logs` | `false` | Keep a per-attempt execution log in Redis. Off by default: it is a debugging aid, not something the engine needs to run a job. Opt in while investigating, then turn it back off |
| `retention.max-execution-logs-per-job` | `50` | Maximum log entries retained per job |

### Redis HA (Sentinel / Cluster)

| Property | Default | Description |
|---|---|---|
| `redis.enabled` | `true` | Whether the starter applies its Sentinel/cluster factory wiring |
| `redis.sentinel-master` | — | Redis Sentinel master name (requires `redis.sentinel-nodes`) |
| `redis.sentinel-nodes` | — | Sentinel or Cluster nodes as `host:port` list |
| `redis.cluster-mode` | `false` | Treat `redis.sentinel-nodes` as a Redis Cluster instead of Sentinel |
| `redis.password` | — | Password for authenticated Sentinel/Cluster connections |

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
    jitter-factor: 0.1
  executor:
    core-pool-size: 8
    max-pool-size: 16
    await-termination-seconds: 60
  ttl-days: 7
  idempotency-ttl-hours: 24
  retention:
    clear-payload-on-completion: true
    max-execution-logs-per-job: 100
  rate-limit:
    requests-per-minute: 120
    window-seconds: 30
    circuit-breaker-failures: 5
    circuit-breaker-reset-seconds: 30
  webhook:
    enabled: true
    max-attempts: 5
    backoff-multiplier: 1.5
  uniqueness:
    enabled: true
    ttl-seconds: 120
    defer-seconds: 10
  redis:
    sentinel-master: mymaster
    sentinel-nodes:
      - host1:26379
      - host2:26379
    password: mypassword   # optional
```

---

## Environment Variables & Relaxed Binding

All properties support Spring Boot's relaxed binding. Common equivalences:

| YAML (kebab-case) | Environment Variable | CLI Argument |
|---|---|---|
| `simplydone4j.scheduler.enabled` | `SIMPLYDONE4J_SCHEDULER_ENABLED` | `--simplydone4j.scheduler.enabled=false` |
| `simplydone4j.retry.max-attempts` | `SIMPLYDONE4J_RETRY_MAX_ATTEMPTS` | `--simplydone4j.retry.max-attempts=5` |
| `simplydone4j.rate-limit.requests-per-minute` | `SIMPLYDONE4J_RATE_LIMIT_REQUESTS_PER_MINUTE` | `--simplydone4j.rate-limit.requests-per-minute=120` |
| `simplydone4j.uniqueness.ttl-seconds` | `SIMPLYDONE4J_UNIQUENESS_TTL_SECONDS` | `--simplydone4j.uniqueness.ttl-seconds=120` |
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

All `spring.data.redis.*` properties (timeout, database index, etc.) are honoured for the standalone connection.

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

Sentinel takes precedence over `spring.data.redis.*` when both are present. `spring.data.redis.*` extras (timeouts, database index) are **not** applied to the Sentinel factory.

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

---

## Multi-tenancy

Isolate environments sharing the same Redis with `key-prefix`:

```yaml
# production
simplydone4j:
  key-prefix: "prod"

# staging
simplydone4j:
  key-prefix: "staging"
```

Every Redis key the starter writes is namespaced under it, including the priority queues and uniqueness locks.

---

## Data Retention

Retention differs by data type:

| Data | Redis key | Retention |
|---|---|---|
| Job record (terminal: SUCCESS/FAILED/DLQ/CANCELLED) | `{prefix}:job:<id>` | TTL of `ttl-days×24 + ttl-hours` (default 1 hour), set at terminal transition |
| Execution logs (opt-in) | `{prefix}:log:<id>` | Only written when `retention.store-execution-logs=true` (default `false`). Capped at `retention.max-execution-logs-per-job` entries per job; key TTL = job TTL refreshed on each write |
| Status index members for finished jobs | `{prefix}:idx:status:<status>` | Retained with TTL = job TTL, so `countByStatus(SUCCESS/FAILED/DLQ/CANCELLED)` and the dead-letter health/gauge stay accurate. The TTL is what keeps the index bounded — without it the ZSET would grow forever |
| Status index members for in-flight jobs | `{prefix}:idx:status:<status>` | No TTL; the member is removed as soon as the job leaves that status |
| Priority queues | `{prefix}:queue:<priority>` | No TTL; Redis drops the key when the last job leaves, and a queued job must survive until it runs |
| Idempotency locks | `{prefix}:idempotency:*` | `idempotency-ttl-hours` (default 1) |
| Rate-limit windows | `{prefix}:ratelimit:*` | 2× `window-seconds` |
| Uniqueness locks | `{prefix}:unique:<key>` | `uniqueness.ttl-seconds`, or job timeout + 30s when unset |
| Fencing counter | `{prefix}:fencing:seq` | **Deliberately no TTL.** Tokens must stay monotonic across restarts; if this expired the sequence would restart at 1 and a slow worker's stale token could pass the fence |

**Note:** Jobs stuck in a non-terminal state (`QUEUED`/`RUNNING`/`RETRY_SCHEDULED`) carry no TTL — they are retained until they reach a terminal state or are cancelled. The lease reaper and retry promoter exist precisely to drive stuck jobs toward termination.

```yaml
simplydone4j:
  ttl-days: 90   # 90-day TTL for finished jobs
```