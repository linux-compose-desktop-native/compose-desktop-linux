#!/usr/bin/env bash
# Builds and runs examples/hello exactly as an outside consumer would.
#
# examples/demo depends on project(":library"), so it never exercises the
# published artifacts or the Gradle plugin. This publishes both to the local
# Maven repository and then builds examples/hello — a standalone Gradle build
# that knows nothing except the plugin id — against them.
#
# Fails if the executable does not link, or if it does not render a frame.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CAPPED="$ROOT/tools/capped.sh"
CAPTURE="${CAPTURE:-$(mktemp -d)/hello.png}"

if [[ ! -d "$ROOT/build/maven-local/org/jetbrains/skiko" ]]; then
    echo "The forks are not published yet. Run tools/publish-forks.sh first." >&2
    exit 1
fi

echo "==> Publishing the library and plugin to mavenLocal"
"$CAPPED" "$ROOT/gradlew" -p "$ROOT" \
    :library:publishToMavenLocal :gradle-plugin:publishToMavenLocal \
    --console=plain

echo "==> Building examples/hello as a standalone consumer"
"$CAPPED" "$ROOT/gradlew" -p "$ROOT/examples/hello" \
    linkDebugExecutableLinuxX64 --console=plain

BINARY="$ROOT/examples/hello/build/bin/linuxX64/debugExecutable/hello.kexe"
[[ -x "$BINARY" ]] || { echo "No executable produced at $BINARY" >&2; exit 1; }

echo "==> Running it headlessly"
# Exit 124 is the intended timeout: the application has no reason to stop on its
# own, so surviving the whole window is the success case.
set +e
SDL_VIDEODRIVER=offscreen COMPOSE_NATIVE_CAPTURE="$CAPTURE" timeout 15s "$BINARY"
status=$?
set -e
if [[ $status -ne 0 && $status -ne 124 ]]; then
    echo "The consumer application exited with $status" >&2
    exit 1
fi

[[ -s "$CAPTURE" ]] || { echo "No frame was captured to $CAPTURE" >&2; exit 1; }
echo "==> OK: linked, ran, and rendered a frame to $CAPTURE"
