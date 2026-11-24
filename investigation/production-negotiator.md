# Production dependency checkpoint

The GitProjectHealthServiceServer fat-jar build directly lists jvm-libp2p snapshot 4, but Coursier resolution of its 55 MavenPrebuilt2 roots selects `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-9` through the UrlProtocol dependency graph. The server's UrlResolver 0.0.600 resolves UrlProtocol 0.0.337; UrlProtocol 0.0.332's POM declares snapshot 9.

Snapshot 9's Negotiator source at commit `5335545d58b2e97246b1ed3f7a053da179c3dbd8` has SHA-256 `7a77dd84b58f156fb5b1867fbaa84f96153f9cf418df4d73649f51fec4512bf7`. Its success path fires the selected-protocol event and then unconditionally removes the requester handler. That snapshot's child close path synchronously fires channel-unregistered through the pipeline, which can destroy the pipeline during a re-entrant callback. The real-host reproducer will confirm the complete failure path.
