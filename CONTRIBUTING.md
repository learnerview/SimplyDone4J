# Contributing

## Requirements

- Java 21
- Maven 3.9 or newer
- Redis 7 or newer for the Redis-backed tests (Docker or a local server)

## Build and test

The unit suite runs without Redis; Redis-backed tests are skipped when no server
is reachable.

```bash
mvn -B test
```

Run the full Redis-backed suite against a local server:

```bash
docker run -d --name simplydone4j-redis -p 6379:6379 redis:7-alpine
mvn -B verify -Dit.redis.host=localhost -Dit.redis.port=6379
```

Build and test the demo after installing the starter locally:

```bash
mvn -B install -DskipTests
mvn -B -f demo/pom.xml verify
```

The optional load test is an integration test and is not part of the default
unit suite:

```bash
mvn -B -Dtest=LoadRealRedisIT -Dload.jobs=10000 \
  -Dit.redis.host=localhost -Dit.redis.port=16379 test
```

Use a separate Redis database or port for the load test so it does not mix with
other local state.

## Code guidelines

- Keep the public API in `service`, `handler`, `dto`, `model`, `event`, and
  `exception` packages. `service.impl` and `Redis*` repository classes are
  implementation types.
- Preserve the at-least-once contract. Any new write path that records an
  attempt outcome must remain fenced by the lease token.
- Add a test for behavior changes. Redis-specific atomicity changes should have a
  real-Redis test when the existing test infrastructure supports it.
- Do not add comments that restate the code. Document non-obvious trade-offs and
  guarantees instead.
- Keep configuration changes backward compatible where possible. Add new
  properties with a default value and update `docs/CONFIGURATION.md` and the
  configuration metadata.
- Do not commit credentials, Redis passwords, generated release bundles, or
  local `dump.rdb` files.

## Documentation

Update the relevant guide when behavior, configuration, or operation changes:

- [README](README.md) for setup and scope
- [User Guide](docs/GUIDE.md) for API usage
- [Configuration](docs/CONFIGURATION.md) for properties
- [Operations](docs/OPERATIONS.md) for health, failover, and runbooks
- [Architecture](docs/ARCHITECTURE.md) for algorithms and data model
- [Guarantees](docs/GUARANTEES.md) for the execution contract
- [Verification](docs/VERIFICATION.md) for reproducible checks and observed results

Describe verified behavior and known limits. Do not make availability claims
that are not enforced and tested.

## Pull requests

- Keep the change focused and explain the behavior it changes.
- Include the commands and results used to verify the change.
- Update documentation and tests in the same pull request.
- Do not commit generated files under `target/` or publish from a local machine.
