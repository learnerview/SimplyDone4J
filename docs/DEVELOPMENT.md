# Development Guide

## Requirements

- Java 21+
- Maven 3.8+

No Redis or Docker is needed to build or test: every repository test drives the Redis client through a Mockito mock, and the rest are plain unit tests. A local Redis is only needed to run the demo application or to experiment live.

---

## Build & Test

```bash
# Quick build (skip tests)
mvn clean install -DskipTests

# Full suite
mvn clean verify
```

**Test breakdown:**
- Unit tests with Mockito: handler registry, mappers, retry policies, circuit breaker, DLQ, overlap guard
- Webhook tests: retry budget, backoff, JSON body escaping, failure isolation
- Redis repository tests: jobs, queues, execution logs, uniqueness guard (mocked `StringRedisTemplate`)
- Lease fencing tests: expired lease recovery, token fencing verification
- Stress tests: timeout stress, mixed load, variable duration, throughput

All tests pass with 0 failures, 0 errors.

---

## Local Development

### Running the Demo with Local Redis

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

Then build the library and the demo:

```bash
mvn clean install -DskipTests
cd ../simplydone4j-demo
mvn clean package
java -jar target/simplydone4j-demo-1.0.0.jar
```

Read queue statistics programmatically with `MonitoringService.getStats()` (depths and per-status counts). See the sibling `simplydone4j-demo` project for a runnable end-to-end example.

### IDE Setup

- Open as Maven project
- Enable annotation processing (for `spring-boot-configuration-processor`)

---

## CI Workflow (`.github/workflows/ci.yml`)

Runs on every push to `main`/`develop` and on pull requests to `main`:

1. **Setup**: Java 21, Maven cache
2. **Build**: `mvn clean verify`
3. The enforcer plugin (Java 21 / Maven 3.8+) runs as part of `verify`

---

## Release Workflow (`.github/workflows/release.yml`)

Triggers on GitHub release creation:

1. **Build**: `mvn clean verify`
2. **Sign**: GPG-sign artifacts (`maven-gpg-plugin`)
3. **Deploy**: Publish to Maven Central via `central-publishing-maven-plugin`

**Required secrets:**
- `MAVEN_USERNAME` / `MAVEN_PASSWORD` (Central Portal token)
- `GPG_PRIVATE_KEY` / `MAVEN_GPG_PASSPHRASE` (GPG signing, used by the release workflow)

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
│   │   │   ├── autoconfigure/       # Properties & auto-config classes
│   │   │   ├── dto/                 # Request/Response DTOs
│   │   │   ├── entity/              # JobEntity, JobExecutionLog
│   │   │   ├── event/               # JobEvent, JobEventData, JobEventPublisher
│   │   │   ├── exception/           # Custom exceptions
│   │   │   ├── handler/             # JobHandler, JobContext, HandlerRegistry
│   │   │   ├── mapper/              # JobMapper (JSON ↔ Entity)
│   │   │   ├── metrics/             # JobMetrics, QueueDepthSampler
│   │   │   ├── model/               # JobPriority, JobStatus enums
│   │   │   ├── repository/          # Interfaces + Redis implementations
│   │   │   ├── service/             # Service interfaces + impl packages
│   │   │   │   └── impl/            # All service implementations
│   │   └── resources/
│   │       └── scripts/rate_limit.lua
│   └── test/
│       └── java/...                 # tests
├── docs/                            # This documentation
├── pom.xml
└── README.md
```

---

## Adding a New Feature

1. Create feature branch: `git checkout -b feature/my-feature`
2. Implement with tests (unit tests; mock the `StringRedisTemplate` for repository logic)
3. Update relevant docs in `docs/`
4. Open PR with description of changes
5. CI must pass (all tests green)
6. Squash-merge to `main`
