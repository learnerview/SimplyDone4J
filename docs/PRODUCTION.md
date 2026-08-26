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
| Redis memory | ~1–5 KB per job hash + log list. At 100K jobs/day with 30-day retention, expect 3–15 GB. Finished jobs are purged from status indexes immediately, so index memory stays proportional to in-flight work only. |

---

## 3. Health Checks

Add `spring-boot-starter-actuator` for Redis health checks. Create a custom indicator:

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

Add `micrometer-core` (and `micrometer-registry-prometheus` for Prometheus) and bind job-count gauges:

```java
@Component
public class JobMetricsBinder {

    public JobMetricsBinder(MeterRegistry meterRegistry, JobRepository jobRepository) {
        for (JobStatus status : JobStatus.values()) {
            meterRegistry.gauge("simplydone4j.jobs." + status.name().toLowerCase(),
                    jobRepository, repo -> repo.countByStatus(status));
        }
    }
}
```

### Actuator Endpoints

Enable via `spring-boot-starter-actuator`:

| Endpoint | Description |
|---|---|
| `actuator/health` | Basic health check (incl. custom `JobSystemHealthIndicator`) |
| `actuator/info` | Application info with build metadata |
| `actuator/metrics` | Custom metrics: `simplydone4j.jobs.queued`, `simplydone4j.jobs.running`, `simplydone4j.jobs.success`, `simplydone4j.jobs.dlq` |
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