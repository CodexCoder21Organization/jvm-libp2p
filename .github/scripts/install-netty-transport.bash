#!/usr/bin/env bash
set -euo pipefail

# Build the reviewed transport locally while its Maven release is unpublished.
netty_checkout="$RUNNER_TEMP/netty-auto-read"
git clone --no-checkout https://github.com/CodexCoder21Organization/netty.git "$netty_checkout"
git -C "$netty_checkout" checkout c2c1ce453457abceffdb47efcee507db2d57cd44
(
  cd "$netty_checkout"
  ./mvnw -B -ntp -pl transport -am \
    -Dtest=DefaultChannelPipelineAutoReadTest,NioShutdownReentryTest,NioEventLoopTest \
    -Dsurefire.failIfNoSpecifiedTests=false install
)

mkdir -p "${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
cat > "${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/netty-auto-read.gradle" <<'GRADLE'
settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositories {
        mavenLocal {
            content {
                includeVersion 'io.netty', 'netty-transport', '4.2.10.3-codexcoder21-auto-read'
            }
        }
    }
}
GRADLE
