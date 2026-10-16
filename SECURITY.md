# Security Policy

## Supported Versions

| Version | Supported |
|---|---|
| `2.1.x` | Yes |
| `< 2.1` | No |

Security fixes are applied to the latest `2.1.x` line. The project is a library; upgrading
means bumping the dependency version in your application's POM.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, pull requests or
discussions.**

Instead, report privately to the maintainer:

- **Email:** [sumit1202shukla@gmail.com](mailto:sumit1202shukla@gmail.com)
- **Subject line:** `[SECURITY] simplydone4j <short description>`

Please include, where possible:

- A description of the issue and its impact.
- The affected version(s) and configuration.
- Reproduction steps or a minimal proof of concept.
- Any suggested remediation.

You can expect an acknowledgement within a few business days. Please allow a reasonable window
for a fix to be prepared and released before any public disclosure; credit will be given in the
release notes unless you ask to remain anonymous.

## Security Model and Scope

SimplyDone4J is an **embedded library**. It does not expose a network service of its own; the
attack surface is (a) the Redis instance it talks to and (b) the data your application puts
into it.

### Secret handling

- **Do not commit credentials.** Supply the Redis password via `spring.data.redis.*` or
  `simplydone4j.redis.password`, populated from environment variables or a secrets manager
  (Vault, AWS/GCP/Azure secret stores, Kubernetes Secrets). The property files in this
  repository contain no real credentials.
- **Prefer TLS and authentication in production.** Configure the Redis connection through
  Spring Boot's standard properties. Credentials are held by the connection factory and are
  not written to logs by the starter.
- **`key-prefix` is not a security boundary.** It isolates naming, not access. Two deployments
  sharing one Redis can see and overwrite each other's keys; use separate Redis instances,
  databases, or ACLs when isolation is a security requirement.

### Data at rest and in transit

- **Job payloads and results are stored as plaintext strings** in Redis hashes. `payload` and
  `result` are JSON strings; `retention.clear-payload-on-completion` can drop the payload at a
  terminal state but does not encrypt anything. Do not enqueue secrets in a payload unless your
  Redis is protected accordingly.
- **Webhook callbacks are outbound HTTP POSTs** to a caller-supplied `callbackUrl`. The starter
  does not validate the URL against an allow-list, so if untrusted users can set `callbackUrl`,
  treat it as an SSRF risk and restrict URLs at your submission boundary.
- **Execution logs are opt-in** (`retention.store-execution-logs`, default `false`) and may
  contain error text; enable them deliberately.

### Idempotency and uniqueness keys

Idempotency locks are keyed on `producer + idempotencyKey`, and uniqueness locks on
`uniqueKey`. These keys are caller-supplied. Avoid embedding secrets in them, and scope
`producer` per tenant if multiple tenants share an instance.

## Dependency Surface

The runtime dependencies are Spring Boot starter libraries (Spring Data Redis, validation,
autoconfiguration, Jackson). Actuator and Micrometer are **optional** and only active if you
add them. The starter declares no other third-party runtime dependency; `lombok` is
compile-time only (`provided` scope) and is absent from the published artifact.

## Out of Scope

- Vulnerabilities in Redis itself or in your Redis deployment/topology.
- Misconfiguration (open Redis port, no authentication, shared instance without isolation).
- Issues that require an attacker to already control your application code, JVM, or Redis
  credentials.

## Secret Manager Integration

For production deployments, secrets should not be distributed as plain environment variables.
Instead, integrate a secret manager and inject values at startup:

- **HashiCorp Vault.** Use the Vault Agent sidecar or the Spring Cloud Vault starter to read
  `JWT_SECRET`, `EXTERNAL_AUTH_JWT_SECRET`, database credentials, and webhook signing keys from a
  mounted secret path at startup. The application continues to read from `System.getenv()`, so the
  sidecar can write values to a `.env` file or inject them directly into the container environment
  before the JVM starts.
- **AWS Secrets Manager.** Store secrets as JSON secret values. A small init container or entrypoint
  script fetches the secret via the AWS CLI/SDK and exports the individual keys as environment
  variables before launching the application. Never embed the secret value in the Docker image.
- **Azure Key Vault / GCP Secret Manager.** Equivalent pattern: init container fetches the named
  secret versions and exports them as environment variables; the application reads them via Spring's
  `${ENV_VAR:default}` property resolution.

**Rotation.** Rotate `JWT_SECRET` and webhook signing keys on a schedule. After rotation, restart
the application so the new key material is loaded. Old JWTs signed with the previous secret will
fail verification, which is the intended behavior for compromised-key rotation.

**Do not.** Do not commit `.env`, `.env.production`, `secrets.json`, or any file containing key
material to version control. `.env.example` may contain placeholder names only.

