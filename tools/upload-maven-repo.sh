#!/usr/bin/env bash
# Uploads the fork artifacts in build/maven-local to a Maven repository.
#
# The forks are separate Gradle builds with their own publishing setups, so they
# cannot simply be pointed at this project's target repository. They publish into
# build/maven-local, and this pushes those exact files onward.
#
# Uploading the files verbatim matters: Gradle module metadata (.module) records
# the variants and their attributes, and republishing through maven-publish would
# regenerate it and lose the linuxX64 variant wiring.
#
# Usage:
#   MAVEN_USER=<user> MAVEN_TOKEN=<token> tools/upload-maven-repo.sh [repo-url]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_REPO="$ROOT/build/maven-local"
TARGET="${1:-https://maven.pkg.github.com/linux-compose-desktop-native/compose-desktop-linux}"

: "${MAVEN_USER:?MAVEN_USER must be set}"
: "${MAVEN_TOKEN:?MAVEN_TOKEN must be set}"

[[ -d "$LOCAL_REPO" ]] || {
    echo "No artifacts at $LOCAL_REPO. Run tools/publish-forks.sh first." >&2
    exit 1
}

uploaded=0
failed=0

while IFS= read -r -d '' file; do
    relative="${file#"$LOCAL_REPO"/}"
    # maven-metadata-local.xml describes a local repository only; the remote
    # generates its own.
    case "$relative" in
        *maven-metadata-local.xml*) continue ;;
        *maven-metadata.xml*) continue ;;
    esac

    status=$(curl -sS -o /dev/null -w '%{http_code}' \
        -u "$MAVEN_USER:$MAVEN_TOKEN" \
        -X PUT --upload-file "$file" \
        "$TARGET/$relative" || echo 000)

    case "$status" in
        2*)
            uploaded=$((uploaded + 1))
            ;;
        409)
            # Already published. GitHub Packages refuses to overwrite a release
            # version, which is the behaviour we want; treat it as satisfied.
            echo "  exists: $relative"
            uploaded=$((uploaded + 1))
            ;;
        *)
            echo "  FAILED ($status): $relative" >&2
            failed=$((failed + 1))
            ;;
    esac
done < <(find "$LOCAL_REPO" -type f -print0)

echo "Uploaded $uploaded file(s) to $TARGET"
if [[ $failed -gt 0 ]]; then
    echo "$failed file(s) failed to upload" >&2
    exit 1
fi
