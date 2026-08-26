# Configuration Reference

All properties are under the `simplydone4j.*` prefix and support Spring Boot's relaxed binding (kebab-case in YAML, environment variables with `SIMPLYDONE4J_` prefix, command-line arguments with `--`).

## Complete Property Reference

### Core

| Property | Default | Description |
|---|---|---|
| `scheduler.enabled` | `true` | Enable scheduler + worker maintenance. Set `false` for submission-only nodes |
| `scheduler.polling-interval-ms` | `1000` | How often the scheduler polls queues |
| `scheduler.queue-prefix` | `simplydone4j:queue` | Redis key prefix for priority queues |
| `scheduler.weights.high` | `70` | Scheduling weight for HIGH priority |
| `scheduler.weights.normal` | `20` | Scheduling weight for NORMAL priority |
| `scheduler.weights.low` | `10` | Scheduling weight for LOW priority |

### Rate Limiting

| Property | Default | Description |
|---|---|---|
| `rate-limit.requests-per-minute` | `60` | Max submissions per producer per window |
| `rate-limit.window-seconds` | `60` | Rate limit sliding window in seconds |
| `rate-limit.circuit-breaker.failures` | `5` | Failures before circuit opens |
| `rate-limit.circuit-breaker.reset-seconds` | `30` | Seconds before half-open retry |
| `rate-limit.circuit-breaker.slow-call-ms` | `2000` | Duration threshold for slow-call detection |

### Retry

| Property | Default | Description |
|---|---|---|
| `retry.max-attempts` | `3` | Max attempts (including initial try) |
| `retry.initial-delay-seconds` | `5` | Delay before first retry |
| `retry.backoff-multiplier` | `2.0` | Exponential backoff multiplier |

### Worker

| Property | Default | Description |
|---|---|---|
| `worker.lease-timeout-seconds` | `30` | Max lease hold time per worker |
| `worker.retry-promoter-interval-ms` | `1000` | Frequency of retry promotion |
| `worker.lease-reaper-interval-ms` | `5000` | Frequency of lease recovery |

### Queue

| Property | Default | Description |
|---|---|---|
| `queue.max-depth` | `10000` | Max jobs across all priority queues |

### Executor (Thread Pool)

| Property | Default | Description |
|---|---|---|
| `executor.core-pool-size` | `4` | Worker thread pool core size |
| `executor.max-pool-size` | `8` | Worker thread pool max size |
| `executor.queue-capacity` | `100` | Thread pool work queue capacity |
| `executor.keep-alive-seconds` | `60` | Idle thread keep-alive |
| `executor.default-timeout-seconds` | `30` | Default handler execution timeout |
| `executor.await-termination-seconds` | `30` | Graceful shutdown wait |

### Global

| Property | Default | Description |
|---|---|---|
| `key-prefix` | `simplydone4j` | Global Redis key prefix |
| `ttl-days` | `0` | Days component of finished-job data TTL |
| `ttl-hours` | `1` | Hours component of finished-job data TTL (effective TTL = `ttl-days×24 + ttl-hours`, default 1 hour) |
| `idempotency-ttl-hours` | `1` | TTL for idempotency locks |
| `monitoring.enabled` | `true` | Enable MonitoringService |

### Retention

| Property | Default | Description |
|---|---|---|
| `retention.clear-payload-on-completion` | `false` | Clear job payload when job reaches terminal status (SUCCESS/DLQ/CANCELLED) |
| `retention.store-execution-logs` | `true` | Store per-attempt execution logs in Redis |
| `retention.max-execution-logs-per-job` | `50` | Maximum log entries retained per job |

### Redis HA (Sentinel / Cluster)

| Property | Default | Description |
|---|---|---|
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
    circuit-breaker:
      failures: 5
      reset-seconds: 30
      slow-call-ms: 2000
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

All Redis keys are prefixed (`prod:job:...`, `staging:queue:high`, etc.).

---

## Data Retention

Retention differs by data type:

| Data | Redis key | Retention |
|---|---|---|
| Job record (terminal: SUCCESS/DLQ/CANCELLED) | `{prefix}:job:<id>` | TTL of `ttl-days×24 + ttl-hours` (default 1 hour), set at terminal transition |
| Execution logs | `{prefix}:log:<id>` | Fixed 7-day TTL, max 50 entries per job (configurable via `retention.max-execution-logs-per-job`) |
| Status/priority index entries for finished jobs | `{prefix}:idx:*` | Removed immediately at terminal transition (no zombies left behind) |
| Idempotency locks | `{prefix}:idempotency:*` | `idempotency-ttl-hours` (default 1) |
| Rate-limit windows | `{prefix}:ratelimit:*` | 2× `window-seconds` |

**Note:** Jobs stuck in a non-terminal state (`QUEUED`/`RUNNING`/`RETRY_SCHEDULED`) carry no TTL — they are retained until they reach a terminal state or are cancelled. The lease reaper and retry promoter exist precisely to drive stuck jobs toward termination.

```yaml
simplydone4j:
  ttl-days: 90   # 90-day TTL for finished jobs
```