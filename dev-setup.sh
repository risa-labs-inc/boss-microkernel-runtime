#!/usr/bin/env bash
# Wires the standalone runtime build to a sibling BossConsole checkout.
# Builds the upstream IPC jars in BossConsole and points this repo's
# `useLocalDependencies` branch at them.
#
# Layout assumed:
#   ~/Development/Boss/BossConsole                       (sibling)
#   ~/Development/Boss/boss_plugins/boss-microkernel-runtime  (this repo)
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

# The plugin API contract comes from a different repo and is NOT in the jars above —
# see AGENTS.md. The build downloads it when no local copy exists, so this is a
# report rather than a fetch: a sibling checkout is only needed to test an
# unreleased API revision.
API_VERSION="$(sed -n 's/^boss\.plugin\.api\.version=//p' gradle.properties)"
API_JAR="boss-plugin-api-${API_VERSION}.jar"
echo
if [[ -f "../boss-plugin-api/build/libs/$API_JAR" ]]; then
    echo "✓ boss-plugin-api $API_VERSION from the sibling checkout"
elif [[ -f "$BOSS/plugin-platform/plugin-api-core/build/api-contract/$API_JAR" ]]; then
    echo "✓ boss-plugin-api $API_VERSION from BossConsole's fetched copy"
else
    echo "• boss-plugin-api $API_VERSION will be downloaded on first build"
    echo "  (build it in a sibling boss_plugins/boss-plugin-api checkout to use an unreleased one)"
fi

echo
echo "Now run: ./gradlew fatJar"
echo
echo "The fatJar will be at build/libs/boss-microkernel-runtime-<version>-all.jar."
echo "Drop it into ~/.boss/plugins/ to test against your local BossConsole."
