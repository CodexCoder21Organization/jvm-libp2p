# Publication status

2026-10-22 10:02 UTC — RUNNING.

Request: publish jvm-libp2p from exactly e15e0f1947842be7ef6836c116c14ba47cc8eea1, verify served files, and report the consumer coordinate edit. No consumer changes or other repository writes are allowed.

Completed: fresh checkout, fork release-history review, current consumer coordinate lookup, version availability checks, previous release bytecode comparison, external Gradle release configuration, and read-back verification tooling. Snapshot-38 is the selected unused version. Snapshot-37 has the older hook signature and lacks payload merging.

Current step: waiting for the machine-wide full-suite lock before local Gradle compilation. The build has not begun; its command has a hard deadline. This lane has not uploaded an artifact or launched remote builds. The only repository writes are this evidence branch. No agents were delegated and no PR was opened.

Owner direction: exact source commit, no source/consumer modifications, no overwrites, publish explicitly authorized for this lane only. These are recorded in PUBLIBP2P.md.

Holds: compilation waits for the common machine resource lock. Upload depends on the local JAR/POM; read-back verification depends on upload. No user decision is needed.

Next steps: build locally, check version availability again, upload with publisher 0.0.6, verify SHA-256 and merged bytecode, push final evidence, and confirm all lane background processes ended.

Lessons: an assigned source version is not proof of publication contents; inspect the served JAR. Keep exact-source build metadata outside the checkout when only evidence writes are permitted. Push release evidence before shared-resource waits so another invocation can resume without repeating the investigation.
