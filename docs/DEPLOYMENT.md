# Deployment Guide

SimplyDone4J is an embedded library, so "deploying" it means deploying **your** Spring Boot
application with Redis reachable. There is no scheduler daemon, no worker process and no schema
to migrate. This guide covers packaging, Redis provisioning and rollout; runtime hardening
lives in [PRODUCTION.md](PRODUCTION.md).

---

## 1. Build and Package

```bash
mvn -B clean verify        # enforces Java 21 / Maven 3.8+, runs the tests
mvn -B package             # produces your application jar
```

The starter itself publishes as a single jar (`simplydone4j-spring-boot-starter-2.1.0.jar`,
measured at ~175 KB) with no transitive runtime dependency beyond Spring Boot libraries.
Lombok is compile-time only and is absent from the published artifact.

Requirements: **Java 21** at build and runtime; **Redis 6/7** at runtime.

---

## 2. Provision Redis

Production should use authentication and, ideally, TLS and HA.

### Standalone with authentication

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --requirepass mypassword
```

```yaml
spring:
  data:
    redis:
      url: redis://:mypassword@localhost:6379
      # timeout, database index, etc. are all honoured here
```

### Sentinel and Cluster

Use `simplydone4j.redis.*` instead of `spring.data.redis.url` (see
[CONFIGURATION.md](CONFIGURATION.md#redis-ha-sentinel--cluster)):

```yaml
simplydone4j:
  redis:
    sentinel-master: mymaster
    sentinel-nodes:
      - host1:26379
      - host2:26379
    password: mypassword        # optional
```

```yaml
simplydone4j:
  redis:
    cluster-mode: true
    sentinel-nodes:
      - host1:6379
      - host2:6379
```

When Sentinel/Cluster is configured the starter contributes its own
`LettuceConnectionFactory`; otherwise it leaves Spring Boot's default factory in place. Sentinel
takes precedence over `spring.data.redis.*` when both are present, and Boot's extra connection
options (timeouts, database index) are **not** applied to the Sentinel factory.

> Every atomic operation the engine performs is single-key, so it is safe on Redis Cluster.
> The starter builds a connection for the topology but does not manage failover, resharding or
> key slots itself.

---

## 3. Configuration via Environment Variables

All properties support Spring Boot relaxed binding, so a container can be configured entirely
from the environment:

```bash
SPRING_DATA_REDIS_URL=redis://redis:6379
SIMPLYDONE4J_SCHEDULER_ENABLED=true
SIMPLYDONE4J_QUEUE_MAX_DEPTH=5000
SIMPLYDONE4J_KEY_PREFIX=prod
```

List properties (`simplydone4j.redis.sentinel-nodes`) accept comma-separated values.

---

## 4. Docker Compose

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

For Sentinel/Cluster, configure `simplydone4j.redis.*` in `application.yml` and omit
`spring.data.redis.url`.

---

## 5. Rolling Upgrades and Scaling

- **Horizontal scaling is safe.** Multiple instances coordinate through Redis: queue claims use
  `WATCH`/`MULTI` so exactly one worker removes a job, and fencing tokens prevent a superseded
  worker from overwriting a newer attempt. You can run several copies of the same application.
- **Drain before stopping.** The executor waits for in-flight handlers up to
  `executor.await-termination-seconds`; give the orchestrator a termination grace period at least
  that long.
- **Submission-only nodes.** Set `simplydone4j.scheduler.enabled=false` on instances that should
  accept submissions but not execute them (for example a web tier), leaving execution to a worker
  tier. Both tiers share the same Redis.
- **Watch the reaper after a deploy.** A short burst of `simplydone4j.fencing.rejections` and
  `simplydone4j.lease.reaped` right after a rollout is the expected consequence of one instance
  taking over another's in-flight leases. A *sustained* stream means leases are too short for your
  handlers.
- **No schema migration.** There is nothing to migrate between versions; keys carry TTLs and the
  fencing counter is intentionally permanent. Changing `key-prefix` starts a fresh namespace.

---

## 6. Related Documentation

- [CONFIGURATION.md](CONFIGURATION.md) — every property, defaults, Redis HA, retention.
- [PRODUCTION.md](PRODUCTION.md) — sizing, shutdown, health, metrics, debugging.
- [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) — scored readiness assessment and gaps.
- [GUIDE.md](GUIDE.md) — the application-facing API.
