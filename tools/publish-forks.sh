#!/usr/bin/env bash
# Publishes the Skiko and Compose Multiplatform Core forks for linuxX64.
#
# Neither upstream publishes Kotlin/Native Linux artifacts, so they have to be
# built from the forks in third_party/ before anything can depend on them.
#
# Always publishes into build/maven-local. The forks are separate Gradle builds
# with their own publishing setups and cannot be pointed at an arbitrary remote,
# so pushing these artifacts onward is tools/upload-maven-repo.sh's job.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CAPPED="$ROOT/tools/capped.sh"
LOCAL_REPO="$ROOT/build/maven-local"

# Separate Gradle homes: these builds want different Gradle versions and
# settings from each other and from the root project.
SKIKO_HOME="${GRADLE_HOMES:-$HOME/.gradle-homes}/skiko"
CORE_HOME="${GRADLE_HOMES:-$HOME/.gradle-homes}/compose-core"
mkdir -p "$SKIKO_HOME" "$CORE_HOME"

publish_args=(-Dmaven.repo.local="$LOCAL_REPO")
skiko_task="publishLinuxX64PublicationToMavenLocal"
core_task_suffix="PublicationToMavenLocal"

echo "==> Publishing Skiko"
cd "$ROOT/third_party/skiko/skiko"
GRADLE_USER_HOME="$SKIKO_HOME" "$CAPPED" ./gradlew "$skiko_task" \
    --no-daemon --console=plain \
    -Pskiko.awt.enabled=false \
    -Pskiko.native.enabled=true \
    -Pskiko.native.linux.enabled=true \
    "${publish_args[@]}"

# Order matters only for readability; Gradle resolves the graph itself.
CORE_MODULES=(
    ":navigationevent:navigationevent"
    ":navigationevent:navigationevent-compose"
    ":compose:runtime:runtime"
    ":compose:runtime:runtime-saveable"
    ":compose:ui:ui-util"
    ":compose:ui:ui-geometry"
    ":compose:ui:ui-unit"
    ":compose:ui:ui-graphics"
    ":compose:ui:ui-text"
    ":compose:ui:ui-backhandler"
    ":compose:ui:ui-skiko"
    ":compose:ui:ui"
)

echo "==> Publishing Compose Multiplatform Core"
cd "$ROOT/third_party/compose-multiplatform-core"
core_tasks=()
for module in "${CORE_MODULES[@]}"; do
    core_tasks+=("${module}:publishLinuxX64${core_task_suffix}")
done
GRADLE_USER_HOME="$CORE_HOME" "$CAPPED" ./gradlew "${core_tasks[@]}" \
    --no-daemon --console=plain --no-configuration-cache \
    "${publish_args[@]}"

echo "==> Done: artifacts in $LOCAL_REPO"
