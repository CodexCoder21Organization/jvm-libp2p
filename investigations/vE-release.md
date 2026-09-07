# Snapshot 27 artifact release

OBSERVED: Built from https://github.com/CodexCoder21Organization/jvm-libp2p/commit/62580dfc with the existing source fix and version 1.3.0-codexcoder21-snapshot-27. Gradle :libp2p:jar and :libp2p:generatePomFileForMavenJavaPublication succeeded with one worker and 512 MiB heap. Uploaded only the generated main JAR and full-dependency POM through community.kotlin.maven.artifact.publishing:community-kotlin-maven-artifact-publishing:0.0.5 HTTP publisher. No running service was deployed.

At 2026-09-07 20:00 UTC both served files returned HTTP 200 and matched local SHA-256:

- [JAR](https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-27/jvm-libp2p-1.3.0-codexcoder21-snapshot-27.jar): `483e5fca32fe3870c7b93c055885b3dce03ceace40d1b1fbfb75ee9320d8106b`
- [POM](https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-27/jvm-libp2p-1.3.0-codexcoder21-snapshot-27.pom): `d964de6be86a8bdb93404e84abdac84c3d623b455d031aeab679b2ef228c96e8`

The final native test run remains pending. Consumer adoption and new public ownership tests continue in https://github.com/CodexCoder21Organization/UrlResolver/pull/1080.
