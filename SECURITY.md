# Security Policy

## Supported versions

Security fixes are applied on a best-effort basis to the current source line
(`3.0.x`) and the latest published release. There is no response or fix SLA, and
older releases are not supported. If you use an older version, upgrade before
requesting a fix. Check
[Maven Central](https://central.sonatype.com/artifact/io.github.learnerview/simplydone4j-spring-boot-starter)
for the latest published version.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Report it privately
through GitHub Security Advisories:

<https://github.com/learnerview/simplydone4j/security/advisories/new>

Include the affected version, a description of the issue, reproduction steps if
available, and the impact you believe it has. Reports are reviewed on a
best-effort basis; there is no acknowledgement or response SLA. Please allow time
for a fix and coordinated disclosure before publishing details.

## Scope

The library stores queue, job, lease, retry, and idempotency state in Redis. It
does not encrypt payloads or Redis traffic. Deployment owners are responsible
for:

- Redis network isolation, authentication, ACLs, and TLS where required
- secret management for Redis credentials
- access to the Redis instance and its backups
- avoiding secrets in job payloads and exception messages
- keeping application and Redis clocks synchronized

The built-in Sentinel and Cluster connection factory supports a password but
does not add TLS or separate Sentinel authentication. Provide a custom
`RedisConnectionFactory` when those settings are required.

See [Operations](docs/OPERATIONS.md#security) for deployment guidance.

## Dependency checks

The verification record includes a local OSV scan performed on 2026-11-05.
Dependency scanning is not part of CI, and results are environment- and
date-specific; they are not a guarantee that no future advisory will affect a
dependency. See [Verification](docs/VERIFICATION.md) for the command and the
recorded result.
