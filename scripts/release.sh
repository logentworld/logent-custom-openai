#!/usr/bin/env bash
# patch 버전·검증·커밋·태그·푸시·게시를 한 번에 실행해요.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $# -ge 2 ]] || { echo "사용법: ./scripts/release.sh 'English summary' '한국어 해요체 본문' [Gradle 인자...]" >&2; exit 1; }
summary="$1"; body="$2"; shift 2
[[ "$summary" != *$'\n'* ]] && LC_ALL=C grep -q '^[ -~]*[A-Za-z][ -~]*$' <<< "$summary" || { echo '제목은 한 줄 영어로 써 주세요.' >&2; exit 1; }
[[ "$body" =~ 요([.!?]|…)*[[:space:]]*$ ]] || { echo '본문은 한국어 해요체로 써 주세요.' >&2; exit 1; }
: "${LOGENT_CONTENT_SIGNING_KEY_FILE:?운영 서명 개인 키 경로를 지정해 주세요.}"
for tool in git gh python3 openssl; do command -v "$tool" >/dev/null || { echo "필수 도구가 없어요: $tool" >&2; exit 1; }; done
gh auth status >/dev/null
branch="$(git symbolic-ref --quiet --short HEAD)"
[[ "$branch" == main || "$branch" == logent ]] || { echo 'main 또는 logent 브랜치에서 릴리스해 주세요.' >&2; exit 1; }
remote_branch="$(git ls-remote --heads origin "refs/heads/$branch")"
if [[ -n "$remote_branch" ]]; then
    git fetch origin "$branch" --quiet
    [[ "$(git rev-parse HEAD)" == "$(git rev-parse FETCH_HEAD)" ]] || { echo '원격 브랜치와 먼저 동기화해 주세요.' >&2; exit 1; }
fi
previous="$(cat VERSION)"
next="$(python3 - "$previous" <<'PY'
import re
import sys
value = sys.argv[1]
if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', value):
    raise SystemExit('VERSION은 x.y.z 형식으로 써 주세요.')
a, b, c = map(int, value.split('.'))
print(f'{a}.{b}.{c + 1}')
PY
)"
tag="v$next"
[[ -z "$(git ls-remote origin "refs/tags/$tag")" ]] || { echo '이미 있는 원격 태그예요.' >&2; exit 1; }
if git show-ref --verify --quiet "refs/tags/$tag"; then echo '이미 있는 로컬 태그예요.' >&2; exit 1; fi
start="$(git rev-parse HEAD)"
restore_version() {
    result=$?
    if [[ "$(git rev-parse HEAD)" == "$start" ]]; then printf '%s\n' "$previous" > VERSION; fi
    exit "$result"
}
trap restore_version EXIT
printf '%s\n' "$next" > VERSION
./scripts/test.sh "$@"
./scripts/build.sh "$@"
title="🚀 Release logent-custom-openai $tag: $summary"
git add -A
git commit -m "$title" -m "$body"
git tag -a "$tag" -m "$title"
trap - EXIT
git push --atomic origin "HEAD:refs/heads/$branch" "refs/tags/$tag"
./scripts/publish-release.sh "$body"
