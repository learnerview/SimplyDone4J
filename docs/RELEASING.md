# Releasing

This project publishes `io.github.learnerview:simplydone4j-spring-boot-starter`
through the Sonatype Central Portal. Local builds do not publish.

## Release checklist

1. Confirm the version in `pom.xml` and `demo/pom.xml` matches the intended tag.
2. Update `CHANGELOG.md`, `README.md`, and any changed documentation.
3. Run the Redis-backed tests and the demo tests.
4. Build the release bundle locally and check the artifacts.
5. Commit the release changes and create an annotated tag such as `v3.0.0`.
6. Create a GitHub Release for that tag. The release workflow publishes the
   Central bundle.
7. Open the Central Portal and publish the uploaded bundle if `autoPublish` is
   disabled. It is disabled in this project's POM.
8. Verify the artifact resolves from Maven Central, then announce the release.

## Local verification

Start Redis first. The Redis-backed tests are skipped when no server is
reachable, so pass the host and port explicitly:

```bash
docker run -d --name simplydone4j-release-redis -p 6379:6379 redis:7-alpine
mvn -B clean verify -Dit.redis.host=localhost -Dit.redis.port=6379
mvn -B -f demo/pom.xml clean verify
```

Build the release bundle without signing:

```bash
mvn -B -Prelease -DskipTests package
```

The release profile attaches source and Javadoc jars and configures the Central
publishing plugin. Expected files in `target/` include:

```text
simplydone4j-spring-boot-starter-3.0.0.jar
simplydone4j-spring-boot-starter-3.0.0-sources.jar
simplydone4j-spring-boot-starter-3.0.0-javadoc.jar
```

The signing plugin is bound to the `verify` phase and runs only when the
`release` profile is active. A local `package` build therefore does not sign.
Do not run a local `deploy`; the workflow owns publication.

## GitHub Actions release workflow

The workflow is triggered when a GitHub Release is created:

```text
.github/workflows/release.yml
```

It performs these steps:

1. Imports the GPG private key.
2. Configures Maven Central credentials for the `central` server id.
3. Runs `mvn -B clean verify` against a Redis 7 service container.
4. Runs `mvn -B deploy -Prelease -DskipTests` to upload the signed bundle.
5. Leaves publication in the Central Portal for manual approval because
   `autoPublish` is `false`.

Required repository secrets:

| Secret | Purpose |
|---|---|
| `GPG_PRIVATE_KEY` | ASCII-armored private key used to sign artifacts |
| `MAVEN_GPG_PASSPHRASE` | Passphrase for the GPG key |
| `MAVEN_USERNAME` | Central Portal user token name |
| `MAVEN_PASSWORD` | Central Portal user token password |

The Maven `central` server entry is created by `actions/setup-java` from
`MAVEN_USERNAME` and `MAVEN_PASSWORD`. Do not put credentials in `pom.xml`,
`settings.xml`, or the repository.

## Version and tag rules

- The POM version must be a release version, not a snapshot, before the GitHub
  Release is created.
- The tag should match the project version, for example `v3.0.0`.
- The demo POM references `simplydone4j.version`; update it with the starter
  version.
- A version already present in Maven Central cannot be replaced. Publish a new
  patch version instead.

## Release notes

Include:

- the supported Java and Spring Boot versions
- notable behavior or configuration changes
- any migration steps or known limitations
- a link to the verification results and the changelog

Do not make availability claims beyond the [Guarantees](GUARANTEES.md) page.

## Troubleshooting

| Problem | Check |
|---|---|
| Signing fails | GPG key import, `MAVEN_GPG_PASSPHRASE`, and loopback pinentry support |
| Redis-backed tests are skipped | Start Redis and pass `-Dit.redis.host` and `-Dit.redis.port` |
| Deploy is rejected with `401` | `MAVEN_USERNAME` and `MAVEN_PASSWORD` must be Central Portal token credentials, not the account password |
| Deploy reports a duplicate version | The version already exists in Central; do not overwrite it |
| Bundle uploads but is not visible | `autoPublish=false`; approve the bundle in the Central Portal |
| Javadoc build emits warnings | The release profile sets `failOnError=false`; fix warnings when practical |
