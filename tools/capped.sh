#!/usr/bin/env bash
# Runs a command inside a transient systemd scope capped at 10 GB.
#
# The gradle.properties files bound each JVM's heap, but the Kotlin/Native link
# step shells out to LLVM/lld as a plain native process that -Xmx cannot reach.
# This scope is a kernel-enforced ceiling over the whole process tree, so a
# runaway step is throttled and then killed inside the scope instead of letting
# the kernel pick a victim elsewhere on the system.
#
# Usage: tools/capped.sh ./gradlew :app:linkDebugExecutableLinux
set -euo pipefail

: "${MEMORY_MAX:=10G}"

exec systemd-run --user --scope --quiet --collect \
  -p "MemoryMax=${MEMORY_MAX}" \
  -p "MemorySwapMax=0" \
  -- "$@"
