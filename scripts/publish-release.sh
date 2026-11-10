#!/usr/bin/env bash
# 준비한 모든 배포 파일과 서명 상태를 GitHub 릴리스에 게시해요.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GITHUB_REPOSITORY:=logentworld/logent-custom-openai}"
[[ "$GITHUB_REPOSITORY" == logentworld/* ]] || { echo 'logentworld 저장소만 게시할 수 있어요.' >&2; exit 1; }
version="$(tr -d '[:space:]' < VERSION)"
release="${LOGENT_RELEASE_TAG:-v$version}"
notes="${1:-릴리스 패키지를 만들었어요.}"
notes+=$'\n\n'
notes+="$(cat build/dist/INSTALL-NOTE.txt)"
if ! gh release view "$release" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
    extra=()
    [[ "$release" != edge ]] || extra+=(--prerelease)
    # 동시 커밋이 먼저 만들었을 때도 이미 생긴 릴리스로 계속해요.
    # `HEAD` 문자열은 GitHub API가 커밋으로 해석하지 않아요. 현재 커밋 SHA를 고정해요.
    target="$(git rev-parse HEAD)"
    gh release create "$release" --repo "$GITHUB_REPOSITORY" --target "$target" --title "AI 제공자 $release" --notes "$notes" "${extra[@]}" ||
        gh release view "$release" --repo "$GITHUB_REPOSITORY" >/dev/null
fi
assets=(build/dist/logent-custom-openai-v"$version".aar build/dist/logent-custom-openai-v"$version"-assets-source.zip build/dist/SHA256SUMS build/dist/INSTALL-NOTE.txt)
if [[ -n "${LOGENT_CONTENT_SIGNING_KEY_FILE:-}" ]]; then
    assets+=(build/dist/logent-custom-openai-v"$version".lpg)
else
    assets+=(build/dist/logent-custom-openai-v"$version"-ci.lpg)
fi
if [[ "$release" == edge ]]; then
    staging="$(mktemp -d)"
    trap 'rm -rf "$staging"' EXIT
    suffix="${GITHUB_SHA:0:12}-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
    published=()
    for asset in "${assets[@]}"; do
        base="${asset##*/}"
        [[ "$base" != SHA256SUMS ]] || continue
        case "$base" in
            *-ci.lpg) name="${base%-ci.lpg}-$suffix-ci.lpg" ;;
            *.lpg) name="${base%.lpg}-$suffix.lpg" ;;
            *) name="$suffix-$base" ;;
        esac
        cp -- "$asset" "$staging/$name"
        published+=("$staging/$name")
    done
    python3 - "$staging" "${published[@]}" <<'PY'
import hashlib
from pathlib import Path
import sys
root = Path(sys.argv[1])
with (root / 'SHA256SUMS').open('w', encoding='ascii') as output:
    for name in sys.argv[2:]:
        path = Path(name)
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        output.write(f'{digest}  {path.name}\n')
PY
    mv "$staging/SHA256SUMS" "$staging/SHA256SUMS-$suffix"
    assets=("${published[@]}" "$staging/SHA256SUMS-$suffix")
fi
gh release upload "$release" --repo "$GITHUB_REPOSITORY" --clobber "${assets[@]}"
gh release edit "$release" --repo "$GITHUB_REPOSITORY" --notes "$notes"
