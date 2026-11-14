#!/usr/bin/env bash
# 태그와 VERSION이 같은 .lpg만 GitHub 릴리스에 올려요.
set -euo pipefail
cd "$(dirname "$0")/.."
built=false
if [[ "${1:-}" == --built ]]; then built=true; shift; fi
version="$(tr -d '[:space:]' < VERSION)"
tag="v$version"
[[ "$(git rev-parse "$tag^{commit}")" == "$(git rev-parse HEAD)" ]] || { echo "현재 커밋과 VERSION 태그가 달라요." >&2; exit 1; }
if [[ "$built" == false ]]; then
    ./scripts/test.sh
    ./scripts/build.sh
fi
name="logent-custom-openai"
asset="$PWD/build/dist/$name-v$version.lpg"
[[ -s "$asset" ]] || { echo "릴리스 산출물이 없어요: $asset" >&2; exit 1; }
repo="$(git remote get-url origin)"
notes="${1:-.lpg 플러그인 패키지를 배포해요.}"
if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
    gh release upload "$tag" "$asset" --repo "$repo" --clobber
    gh release edit "$tag" --repo "$repo" --notes "$notes"
else
    gh release create "$tag" "$asset" --repo "$repo" --verify-tag --title "$name $tag" --notes "$notes"
fi
