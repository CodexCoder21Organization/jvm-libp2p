# Snapshot 32 publication evidence

Requested source: `ec74179a589bfb7137122f93906e03a24ca19714` (merge of the snapshot-32 version-bump change; exact fork `develop` head at preflight).
Coordinate: `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-32`.

## Preflight

- POM probe at `https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-32/jvm-libp2p-1.3.0-codexcoder21-snapshot-32.pom`: HTTP 404 at 2026-10-15 17:39 UTC.
- Read `README.md` and `FORK.md`; no repository `AGENTS.md` exists.
- `FORK.md` directs a Gradle local Maven publication with `-PmavenArtifactId=jvm-libp2p`, followed by uploading only the main JAR and POM using the publisher CLI and kotlin.directory upload API. Sources and Javadoc are attached to the local Gradle publication but are not included in the fork's upload procedure.
- Only `community.kotlin.libp2p:jvm-libp2p` is published by this release configuration. Other Gradle projects are not configured as Maven publications.
- The build scripts do not record a git revision in the artifact manifest or metadata; provenance will be recorded by source commit plus build and remote checksums.

## Build, upload, and remote probes

Pending.
