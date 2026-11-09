#!/usr/bin/env bash
# Linux CI의 기본 도구와 로컬 ARM64 도구를 구분해서 실행해요.
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
export PYTHONDONTWRITEBYTECODE=1
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
    for candidate in /usr/lib/jvm/java-21-openjdk-{arm64,amd64} /usr/lib/jvm/java-17-openjdk-{arm64,amd64}; do
        if [[ -x "$candidate/bin/javac" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
source scripts/gradle-env.sh
extra=()
case "$(uname -m)" in
    aarch64|arm64)
        if [[ -z "${AAPT2_REAL:-}" ]]; then
            for candidate in /root/android-arm64-tools/build-tools/aapt2 "${ANDROID_HOME:-/root/android-sdk}/build-tools/37.0.0/aapt2"; do
                if [[ -x "$candidate" ]]; then export AAPT2_REAL="$candidate"; break; fi
            done
        fi
        if [[ -n "${AAPT2_REAL:-}" && -z "${AAPT2:-}" ]]; then export AAPT2="$PWD/scripts/aapt2"; fi
        ;;
esac
if [[ -n "${AAPT2:-}" ]]; then
    [[ -x "$AAPT2" ]] || { echo "실행 가능한 AAPT2 경로를 지정해 주세요: $AAPT2" >&2; exit 1; }
    extra+=("-Pandroid.aapt2FromMavenOverride=$AAPT2")
fi
exec ./gradlew --no-daemon --console=plain "${LOGENT_GRADLE_ARGS[@]}" "${extra[@]}" "$@"
