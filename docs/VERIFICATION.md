# Verification

This page records the checks used for the `3.0.0` release candidate and the
observed results. These are point-in-time results for one environment, not a
guarantee that a different deployment will behave the same way. Reproduce the
checks against your own Redis topology before setting operational limits.

## Environment

| Item | Value |
|---|---|
| Date | 2026-11-05 |
| Host | Windows, local development machine |
| Build JVM | Java 21.0.7 |
| Maven | 3.9.6 |
| Spring Boot | 4.1.2 |
| Redis | 8.0.6 under WSL for the local checks; Redis 7 in CI |
| Library version | 3.0.0 |

## Build and test suite

```bash
mvn -B clean install -Dit.redis.host=localhost -Dit.redis.port=16379
mvn -B -f demo/pom.xml clean verify
```

Observed:

- Library suite: 204 tests, 0 failures, 0 errors, 0 skipped.
- Load test: 1 test, 0 failures, 0 errors, 0 skipped (run separately).
- Demo: 15 tests, 0 failures, 0 errors, 0 skipped.
- Redis-backed tests executed: `RedisJobRepositoryWriteSemanticsRealRedisTest`,
  `RedisQueueRepositoryWriteSemanticsRealRedisTest`,
  `RedisIdempotencyServiceRealRedisTest`, `DeadLetterRealRedisTest`, and
  `RetryPromotionRaceRealRedisTest`.
- Lease renewal and fencing unit tests (`LeaseRenewerTest`, `LeaseFencingTest`)
  also ran; they use mocked repositories rather than a live Redis server.

Run the same suite against your own Redis instance:

```bash
mvn -B verify -Dit.redis.host=localhost -Dit.redis.port=6379
```

Without a reachable Redis server, the Redis-backed tests are skipped. A local
green build without Redis is therefore not evidence that the Redis-specific
guarantees ran.

## Load test

`LoadRealRedisIT` runs two application instances against one Redis, submits a
configured number of jobs, consumes them, and checks for duplicates, lost jobs,
and final status totals.

```bash
mvn -B -Dtest=LoadRealRedisIT -Dload.jobs=10000 \
  -Dit.redis.host=localhost -Dit.redis.port=16379 test
```

Observed:

```text
LOAD OK jobs=10000 submit=9320ms total=31475ms submitRate=1073/s endToEnd=318/s
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

This is a burst test, not a soak test. It does not establish a universal
throughput or latency limit. Throughput depends on payload size, handler
duration, Redis topology, network latency, and pool size.

## Health transition during a Redis outage

The demo was run against standalone Redis with `spring.data.redis.timeout=3s`.
Redis was stopped while `/api/health` was polled.

Observed sequence:

```text
200 OK          healthy, Redis reachable
503 Service Unavailable    after 3652 ms, Redis unreachable
200 OK          after Redis returned
```

The unhealthy state was reported instead of the request hanging indefinitely.
The demo's health endpoint returns `503` when `EngineStatusService.healthy()` is
false.

For Sentinel/Cluster, the command timeout is
`simplydone4j.redis.command-timeout-ms` (default 5000 ms). For standalone Redis,
the timeout comes from `spring.data.redis.timeout`.

## Sentinel failover

A three-node local Sentinel setup was used with Redis primary `16380`, replica
`16381`, and a Sentinel process. The demo was configured with:

```yaml
simplydone4j:
  redis:
    sentinel-master: mymaster
    sentinel-nodes:
      - localhost:26379
```

The check:

1. Submit a job through the demo while the original primary is active.
2. Stop the primary and wait for Sentinel promotion.
3. Submit another job after the client reconnects to the promoted node.
4. Read both jobs back and confirm a terminal `SUCCESS` outcome with no duplicate
   terminal record.

Observed:

- Lettuce logged a reconnect from `16380` to `16381`.
- Jobs submitted before and after the failover both reached `SUCCESS`.
- No job was reported as completed twice.

This was a manual failover check, not an automated integration test in the
repository. The guaranteed fencing behavior is covered by the automated tests
listed in [Guarantees](GUARANTEES.md).

## Duplicate submission and execution log

Two submissions with the same producer and idempotency key were sent to the demo
`echo` handler. The check compared the returned job ids and read the job and its
execution log from Redis.

Observed:

```text
same job id: yes
job keys:    1
log entries: 1
```

This confirms the two-phase reservation and duplicate resolution path for that
run. The general behavior and its crash windows are documented in
[Guarantees](GUARANTEES.md) and the [Operational contract](../README.md#operational-contract).

## Dependency scan

OSV-Scanner `v2.7.0` was run locally against the root Maven manifest. This scan is
not part of CI:

```bash
osv-scanner scan source .
```

Observed after upgrading Spring Boot from `4.1.0` to `4.1.2`:

```text
No issues found
```

Before the upgrade, the same scan reported 24 advisories against the `4.1.0`
dependency set. The demo POM references the local `3.0.0` starter, so the
scanner could not resolve the demo's transitive dependency set separately. This
scan is not a substitute for reviewing the full dependency tree or for future
advisory monitoring.

## Release bundle

```bash
mvn -B -Prelease -DskipTests package
```

Observed artifacts in `target/`:

| Artifact | Size |
|---|---|
| `simplydone4j-spring-boot-starter-3.0.0.jar` | 159,111 bytes |
| `simplydone4j-spring-boot-starter-3.0.0-sources.jar` | 81,615 bytes |
| `simplydone4j-spring-boot-starter-3.0.0-javadoc.jar` | 545,274 bytes |

The release workflow runs `verify` against Redis before deploying. Maven Central
publication was not part of this local verification; see [Releasing](RELEASING.md).

## Limits of this record

- The load and failover checks were run on one local environment.
- The Sentinel failover check was manual.
- The OSV result is date-specific and does not cover the demo dependency set
  separately.
- The health timing depends on the configured Redis timeouts and the platform's
  probe deadline.
- A green local build without Redis does not execute the Redis-backed proofs.
