# Production Deployment

## 1. Graceful Shutdown

The thread pool uses `CallerRunsPolicy` and waits for in-flight jobs on shutdown:

```yaml
simplydone4j:
  executor:
    await-termination-seconds: 60
```

- `CallerRunsPolicy`: when the queue is full, the submitting thread executes the task (backpressure).
- On shutdown, the executor waits up to `await-termination-seconds` for in-flight jobs to complete before forcing shutdown.

---

## 2. Resource Sizing

| Component | Guidance |
|---|---|
| Thread pool | `corePoolSize` ≈ CPU cores × 2. `maxPoolSize` handles bursts. |
| Queue capacity | Keep below `maxDepth` to avoid `QueueFullException`. |
| Redis memory | ~1 KB per job hash, plus the execution log list only when `retention.store-execution-logs` is enabled (off by default). Finished jobs carry the same TTL as the job record, so the retention window bounds total growth: at 100K jobs/day over 30 days expect roughly 3 GB of job hashes. Status indexes are TTL'd identically, so index memory tracks retained jobs rather than growing without bound. The fencing counter is a single key and does not grow. |

---

## 3. Health Checks

SimplyDone4J ships a built-in `SimplyDoneHealthIndicator` (registers as `health` via
`spring-boot-starter-actuator`). It reports `UP` while work is draining normally and
`DOWN` once a threshold is crossed or a repository call fails:

| Property | Default | Meaning |
|---|---|---|
| `simplydone4j.health.enabled` | `true` | Enable the indicator |
| `simplydone4j.health.dead-letter-threshold` | `1` | DLQ count at or above reports `DOWN` |
| `simplydone4j.health.max-queue-depth` | `100000` | **Total** depth across all priority queues above this value reports `DOWN`. `0` disables the check |

Set a threshold to `0` to disable that check (e.g. repository-only health without
alerting on a busy queue). The indicator exposes `queueDepths`, `totalQueued`, `running`,
`deadLettered`, and the thresholds as health details.

After a Redis outage it reports `DOWN` with the exception recorded rather than
propagating, so the health endpoint stays responsive and returns 503 instead of 500.
To add your own conditions on top (e.g. a success-rate floor), write a custom
`HealthIndicator` that also injects `MonitoringService`:

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

- Counters: `simplydone4j.jobs.submitted`, `simplydone4j.jobs.submitted.by.priority`
  (tag `priority` = `HIGH`/`NORMAL`/`LOW`), `simplydone4j.jobs.claimed`,
  `simplydone4j.jobs.completed` (tag `outcome` = `SUCCESS`/`FAILED`/`TIMEOUT`/
  `DEAD_LETTER`/`DISCARDED`/`DEFERRED`), `simplydone4j.fencing.rejections`,
  `simplydone4j.lease.reaped`, `simplydone4j.jobs.overlapped`
- Timers: `simplydone4j.scheduler.poll`, `simplydone4j.jobs.duration` (tag `outcome`)
- Distribution: `simplydone4j.scheduler.claims.per.poll`
- Gauges (present by default; set `simplydone4j.metrics.queue-depth=false` to skip the sampling): `simplydone4j.queue.depth`
  (tag `priority` = `HIGH`/`NORMAL`/`LOW`/`all`) and `simplydone4j.jobs.dead.letter`,
  refreshed every `queue-depth-refresh-seconds` (default 30s) on a timer — never on the
  scrape path, so a slow Redis can't stall an actuator scrape

### Actuator Endpoints

Enable via `spring-boot-starter-actuator`:

| Endpoint | Description |
|---|---|
| `actuator/health` | Basic health check (incl. custom `SimplyDoneHealthIndicator`) |
| `actuator/info` | Application info with build metadata |
| `actuator/metrics` | Custom metrics: `simplydone4j.jobs.*`, `simplydone4j.queue.depth`, `simplydone4j.scheduler.*`, `simplydone4j.fencing.*`, `simplydone4j.lease.*` |
| `actuator/prometheus` | Prometheus-formatted metrics (if `micrometer-registry-prometheus` added) |

Expose endpoints in `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

---

## 5. Docker

### Redis with Authentication

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --requirepass mypassword
```

### With Spring Configuration

```yaml
spring:
  data:
    redis:
      url: redis://:mypassword@localhost:6379
```

### Docker Compose

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_DATA_REDIS_URL=redis://redis:6379
      - SIMPLYDONE4J_SCHEDULER_ENABLED=true
    depends_on:
      - redis
```

For Sentinel/Cluster, configure `simplydone4j.redis.*` in your `application.yml` (see [CONFIGURATION.md](CONFIGURATION.md#redis-ha-sentinel--cluster)) and omit `spring.data.redis.url`.

---

## 6. Debugging Checklist

1. `redis-cli ping` — check Redis connectivity
2. `redis-cli ZCARD simplydone4j:queue:high` — verify queues exist
3. Check `simplydone4j.scheduler.enabled=true` in config if jobs aren't being picked up
4. `redis-cli HGETALL simplydone4j:job:<jobId>` — inspect job data
5. Monitor thread pool active count via `actuator/metrics`
6. `redis-cli CLIENT LIST` — check connected clients
7. Verify `simplydone4j.key-prefix` if multiple environments share Redis