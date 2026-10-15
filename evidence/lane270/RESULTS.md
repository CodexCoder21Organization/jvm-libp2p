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

## Local build

- Source checkout: `ec74179a589bfb7137122f93906e03a24ca19714`; the fork `develop` head was this same commit. Gradle build completed with exit 0 using `JAVA_OPTS=-Xmx2g`, one worker, and `--no-daemon`.
- Gradle tasks included clean, protobuf generation, Dokka Javadoc/JAR generation, Kotlin/Java compilation, and `publishToMavenLocal`. Tests were not run by this artifact task.
- The main JAR manifest contains only `Manifest-Version: 1.0`; no source commit is embedded in JAR or metadata. The exact checked-out commit and build log establish source provenance.
- Generated local artifact checksums:
  - Main JAR: `ed318e37320ac46ab174b26969f08d762b0dc6e8de3b615988112a3e46afeb35`
  - POM: `fc421c87f3bfe55c128bd2e4ff5dec5860b847eaa8e30626023bf6553beb4fdc`
  - Sources JAR (generated locally; excluded from upload by FORK.md): `ad7e422772f9ea43189f752fe45d0a42a4e68ede0e9641abe198db0b6078d554`
  - Javadoc JAR (generated locally; excluded from upload by FORK.md): `872b027ff43ecaa6f3161f758e45738fa0aa098b0e5f26094db23fe2827b4565`
  - Gradle `.module` (excluded from upload by FORK.md): `b34421d4c1d9c95e3e65e4b05eb4b68f93592db856a5a94ebedbcfbb4dd1ddd0`
- Immediately before publication, the POM probe again returned HTTP 404.

## Upload and remote probes

Pending.

## Upload and remote probes

Published using `community.kotlin.maven.artifact.publishing:community-kotlin-maven-artifact-publishing:0.0.6` and `https://api.kotlin.directory/upload`. The CLI accepted the upload after its interactive confirmation; `--force` was not used.

Complete published coordinate list (the only Maven publication configured by this fork at this version):

| Coordinate | File | Probe | SHA-256 | Match to staged file |
|---|---|---|---|---|
| `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-32` | [POM](https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-32/jvm-libp2p-1.3.0-codexcoder21-snapshot-32.pom) | HTTP 200 | `fc421c87f3bfe55c128bd2e4ff5dec5860b847eaa8e30626023bf6553beb4fdc` | Yes, byte-for-byte |
| `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-32` | [JAR](https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-32/jvm-libp2p-1.3.0-codexcoder21-snapshot-32.jar) | HTTP 200 | `ed318e37320ac46ab174b26969f08d762b0dc6e8de3b615988112a3e46afeb35` | Yes, byte-for-byte |

Sources JAR, Javadoc JAR, and Gradle `.module` metadata were generated into the local Maven repository but excluded from upload by the fork's stated release procedure. No consumer repository was cloned or changed in this lane.
