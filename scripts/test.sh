#!/usr/bin/env bash
# JVM 단위 테스트와 Android lint를 함께 실행해요.
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
export PYTHONDONTWRITEBYTECODE=1
./scripts/gradle.sh :plugin:testDebugUnitTest :plugin:lint "$@"
