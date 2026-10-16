# Development Guide

## Requirements

- **Java 21+** (enforced at build time by `maven-enforcer-plugin`)
- **Maven 3.8+** (also enforced)
- **Redis 6/7** — optional, and only for the real-Redis integration tests and live experiments

No Redis or Docker is needed to build or to run the default test suite. Most tests drive the
Redis client through a Mockito mock; the real-Redis tests are **skipped automatically** when no
server is reachable (see [Test Breakdown](#test-breakdown)).

---

## Build & Test

```bash
# Quick build without tests
mvn clean install -DskipTests

# Full build with the enforcer (Java 21 / Maven 3.8+) and all tests
mvn clean verify
```

To run the real-Redis integration tests, start a Redis and point the suite at it:

```bash
docker run -d --name redis-it -p 16379:6379 redis:7-alpine
mvn test                       # picks up localhost:16379 by default

# or override the endpoint
mvn test -Dit.redis.host=localhost -Dit.redis.port=6379
# equivalently via environment variables:
IT_REDIS_HOST=localhost IT_REDIS_PORT=6379 mvn test
```

### Test Breakdown

- **Unit tests (Mockito):** handler registry, mappers, retry policies, circuit breaker, DLQ,
  overlap guard, submission, executor, webhook retry/backoff/JSON escaping, metrics, health,
  auto-configuration and optional-dependency isolation.
- **Redis repository tests (mocked `StringRedisTemplate`):** jobs, queues, execution logs,
  uniqueness guard, terminal status index.
- **Real-Redis integration tests:** `*RealRedisTest` classes that pin behavior a mock cannot
  prove — atomic per-queue admission, `WATCH`/`MULTI` claim, lease fencing, the retry-promotion
  race, and rate limiting. **These execute 0 tests (abort) when no Redis is reachable.**
- **Stress tests:** timeout, mixed load, variable duration and throughput, using in-JVM fakes.

**Measured on this checkout (no Redis available):** `213 tests run, 0 failures, 0 errors,
0 skipped`, with 6 real-Redis classes aborted — i.e. 21 test methods were not exercised. Run the
suite against a real Redis to cover them.

This makes the suite honest but not self-sufficient: the atomicity guarantees the documentation
leans on are proven only where a live Redis is provided.

---

## Local Development

### Running Against a Local Redis

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
mvn clean install -DskipTests
```

Read queue statistics programmatically with `MonitoringService.getStats()` (per-priority depths
and per-status counts). This project is the library itself; use it from your own Spring Boot
application or from a sibling demo project that depends on the starter.

### IDE Setup

- Open as a Maven project.
- Enable annotation processing (the `spring-boot-configuration-processor`).

---

## CI Workflow (`.github/workflows/ci.yml`)

Runs on pushes to `main`/`develop` and on pull requests to `main`:

1. Checkout, set up Temurin **Java 21**, enable Maven caching.
2. `mvn -B clean verify` (the enforcer runs as part of `verify`).

CI does **not** currently provision a Redis service, so the `*RealRedisTest` classes abort there
exactly as they do locally without one.

---

## Release Workflow (`.github/workflows/release.yml`)

Triggers when a GitHub release is created:

1. Import the GPG key.
2. Set up Java 21 and the Central Portal server credentials.
3. `mvn -B clean verify`.
4. `mvn -B deploy -Prelease -DskipTests` — the `release` profile builds sources + javadoc jars,
   GPG-signs the artifacts and publishes to Maven Central via `central-publishing-maven-plugin`
   (`autoPublish=false`).

**Required secrets:**
- `MAVEN_USERNAME` / `MAVEN_PASSWORD` — Central Portal token
- `GPG_PRIVATE_KEY` / `MAVEN_GPG_PASSPHRASE` — GPG signing

---

## Git Conventions

- Branch naming: `feature/*`, `bugfix/*`, `hotfix/*`, `release/*`
- Conventional Commits preferred: `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`
- PRs should include test coverage for new logic

---

## Project Layout

```
SimplyDone4J/
├── src/
│   ├── main/
│   │   ├── java/io/github/learnerview/simplydone4j/
│   │   │   ├── autoconfigure/   # @ConfigurationProperties + auto-config classes
│   │   │   ├── dto/             # Request/Response DTOs
│   │   │   ├── entity/          # JobEntity, JobExecutionLog
│   │   │   ├── event/           # JobEvent, JobEventData, JobEventPublisher
│   │   │   ├── exception/       # Custom exceptions
│   │   │   ├── handler/         # JobHandler, JobContext, HandlerRegistry
│   │   │   ├── health/          # SimplyDoneHealthIndicator
│   │   │   ├── mapper/          # JobMapper (JSON ↔ Entity)
│   │   │   ├── metrics/         # JobMetrics, MicrometerJobMetrics, QueueDepthSampler
│   │   │   ├── model/           # JobPriority, JobStatus enums
│   │   │   ├── repository/      # Interfaces + Redis implementations
│   │   │   └── service/         # Service interfaces + impl/ implementations
│   │   └── resources/
│   │       ├── META-INF/        # AutoConfiguration.imports, additional metadata JSON
│   │       └── scripts/rate_limit.lua
│   └── test/
│       └── java/...             # unit + *RealRedisTest integration tests
├── docs/                        # Documentation
├── .github/workflows/           # CI + release
├── pom.xml
├── README.md
└── SECURITY.md
```

---

## Adding a New Feature

1. Create a feature branch: `git checkout -b feature/my-feature`.
2. Implement with tests (mock `StringRedisTemplate` for repository logic; add a `*RealRedisTest`
   when the behavior depends on Redis atomicity).
3. Update the relevant docs in `docs/` (and the property tables if you add configuration).
4. Open a PR describing the change; CI (`mvn -B clean verify`) must pass.
5. Squash-merge to `main`.
