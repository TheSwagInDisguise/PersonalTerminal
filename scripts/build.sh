#!/usr/bin/env bash
# Runs unit tests, lint and assembles debug + release APKs.
# Usage: scripts/build.sh [debug|release|all]   (default: all)
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f local.properties ]]; then
  : "${ANDROID_HOME:?Set ANDROID_HOME or create local.properties with sdk.dir=...}"
  echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

target="${1:-all}"
case "$target" in
  debug)   tasks=(testDebugUnitTest lintDebug assembleDebug) ;;
  release) tasks=(testReleaseUnitTest assembleRelease) ;;
  all)     tasks=(testDebugUnitTest lintDebug assembleDebug assembleRelease) ;;
  *) echo "unknown target: $target"; exit 1 ;;
esac

# On a <2 GB machine: ./gradlew -Ppt.testHeapSize=700m testDebugUnitTest --console=plain
./gradlew --console=plain "${tasks[@]}"

echo
echo "artifacts:"
find app/build/outputs/apk -name '*.apk' -exec ls -la {} \;
