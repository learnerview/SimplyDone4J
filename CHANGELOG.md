# Changelog

## 3.0.0

Current source line. Check Maven Central for the latest published version.

- Java 21 and Spring Boot `4.1.2` build.
- Redis-backed job submission, weighted HIGH/NORMAL/LOW priority queues,
  delayed execution, cancellation, and idempotent submission.
- Lease-based execution with token fencing, cooperative timeouts, lease renewal,
  expired-lease recovery, and orphan reconciliation.
- Exponential backoff retries with jitter and a status-indexed dead-letter queue
  with explicit list, replay, and discard operations.
- Per-attempt execution logs, lifecycle events, and a dependency-free
  `EngineStatusService`.
- Sentinel support and Redis Cluster support with a hash-tagged key prefix.
- Pooled Lettuce connections for Sentinel/Cluster plus configurable command and
  connection-pool timeouts.
- ParcelFlow demo application with order fulfillment, workload generation,
  job inspection, and dead-letter recovery.
- Configuration reference, user guide, operations guide, architecture,
  guarantees, verification record, release guide, and security policy.
