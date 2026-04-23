#!/usr/bin/env bash
# Wires the standalone runtime build to a sibling BossConsole checkout.
# Builds the upstream IPC jars in BossConsole and points this repo's
# `useLocalDependencies` branch at them.
#
# Layout assumed:
#   ~/Development/BossConsole               (sibling)
#   ~/Development/boss_plugin/boss-microkernel-runtime  (this repo)
#
# Override with BOSSCONSOLE_DIR=/path/to/BossConsole.
set -euo pipefail

cd "$(dirname "$0")"

BOSS="${BOSSCONSOLE_DIR:-../../BossConsole}"
if [[ ! -d "$BOSS" ]]; then
    echo "❌ BossConsole not found at $BOSS"
    echo "   Set BOSSCONSOLE_DIR to point at your BossConsole checkout."
    exit 1
fi

echo "📦 Assembling upstream IPC jars in $BOSS …"
( cd "$BOSS" && ./gradlew assembleUpstreamJars )

ART_DIR="$BOSS/build/upstream-artifacts"
echo
echo "✓ Upstream jars ready at $ART_DIR:"
ls -lh "$ART_DIR"

echo
echo "Now run: ./gradlew fatJar"
echo
echo "The fatJar will be at build/libs/boss-microkernel-runtime-<version>-all.jar."
echo "Drop it into ~/.boss/plugins/ to test against your local BossConsole."
