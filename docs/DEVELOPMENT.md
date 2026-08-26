# Development Guide

## Requirements

- Java 21+
- Maven 3.8+
- Redis 7+ (local, Docker, or Testcontainers)

---

## Build & Test Locally

### Quick Build (skip tests)

```bash
mvn clean install -DskipTests
```

### Full Test Suite

```bash
# With Testcontainers (starts real Redis in container — requires Docker)
mvn clean test

# With local Redis (no Docker) — faster, but requires Redis on localhost:6379
REDIS_HOST=localhost REDIS_PORT=6379 mvn clean test
```

**Test breakdown (85 tests):**
- Unit tests with Mockito: handler registry, mappers, retry policies, circuit breaker
- Integration tests with Testcontainers: Redis repositories, auto-configuration
- Stress tests: timeout stress, mixed load, variable duration, throughput
- Lease fencing tests: expired lease recovery, token fencing verification

All tests pass with 0 failures, 0 errors.

---

## Local Development

### Starting Redis

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

### Running Tests with Local Redis

For integration-like behavior without Testcontainers:

```bash
export REDIS_HOST=localhost
export REDIS_PORT=6379
mvn clean test
```

This uses your local Redis instance instead of spinning up Testcontainers.

### IDE Setup

- Open as Maven project
- Enable annotation processing (for `spring-boot-configuration-processor`)
- Recommended: enable "Run test with Testcontainers" in IntelliJ

---

## Demo Application

A complete Spring Boot demo is at the `simplydone4j-demo` sibling directory. It demonstrates:

- 4 job handlers (quick-success, failing-task, long-running, callback-test)
- REST API: submit, query, cancel, view stats
- Auto-submission of ~15 jobs on startup
- Job lifecycle events logged to console
- Rate limiting and idempotency tests

**Build and run:**

```bash
# Build the library first
cd SimplyDone4J
mvn clean install -DskipTests

# Build and run the demo
cd ../simplydone4j-demo
mvn clean package
java -jar target/simplydone4j-demo-1.0.0.jar
```

Access `http://localhost:8080/api/jobs/stats` for queue statistics.

---

## CI Workflow (`.github/workflows/ci.yml`)

Runs on every push to `main`/`develop` and on pull requests to `main`:

1. **Setup**: Java 21, Maven cache
2. **Build**: `mvn clean verify` (compiles, runs all 85 tests with Testcontainers)
3. **Enforce**: Java 21 / Maven 3.8+ via enforcer plugin

---

## Release Workflow (`.github/workflows/release.yml`)

Triggers on GitHub release creation:

1. **Build**: `mvn clean verify`
2. **Sign**: GPG-sign artifacts (`maven-gpg-plugin`)
3. **Deploy**: Publish to Maven Central via `central-publishing-maven-plugin`

**Required secrets:**
- `MAVEN_USERNAME` / `MAVEN_PASSWORD` (Central Portal token)
- `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE` (GPG signing)

---

## Git Conventions

```bash
git clone https://github.com/learnerview/simplydone4j.git
cd SimplyDone4J

# Branch naming convention:
# - feature/* for new features
# - bugfix/* for bug fixes
# - hotfix/* for production fixes
# - release/* for release preparation
```

### Commit Style

- Conventional Commits preferred: `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`
- PRs should include test coverage for new logic

---

## Project Layout

```
SimplyDone4J/
├── src/
│   ├── main/
│   │   ├── java/io/github/learnerview/simplydone4j/
│   │   │   ├── autoconfigure/       # Spring Boot auto-config
│   │   │   ├── autoconfigure/       # Properties & auto-config classes
│   │   │   ├── config/              # (empty, reserved)
│   │   │   ├── dto/                 # Request/Response DTOs
│   │   │   ├── entity/              # JobEntity, JobExecutionLog
│   │   │   ├── event/               # JobEvent, JobEventData, JobEventPublisher
│   │   │   ├── exception/           # Custom exceptions
│   │   │   ├── handler/             # JobHandler, JobContext, HandlerRegistry
│   │   │   ├── mapper/              # JobMapper (JSON ↔ Entity)
│   │   │   ├── model/               # JobPriority, JobStatus enums
│   │   │   ├── repository/          # Interfaces + Redis implementations
│   │   │   ├── service/             # Service interfaces + impl packages
│   │   │   │   └── impl/            # All service implementations
│   │   │   └── scripts/             # Lua rate-limit script
│   │   └── resources/
│   │       ├── application-test.yml
│   │       └── scripts/rate_limit.lua
│   └── test/
│       └── java/...                 # 85 tests
├── docs/                            # This documentation
├── simplydone4j-demo/               # Demo app
├── pom.xml
└── README.md
```

---

## Adding a New Feature

1. Create feature branch: `git checkout -b feature/my-feature`
2. Implement with tests (unit + integration if Redis-touching)
3. Update relevant docs in `docs/`
4. Open PR with description of changes
5. CI must pass (85 tests green)
6. Squash-merge to `main`