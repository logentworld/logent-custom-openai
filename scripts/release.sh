#!/usr/bin/env bash
# 검증·버전 갱신·커밋·태그·푸시·GitHub 릴리스를 순서대로 수행해요.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -lt 3 ]]; then
    echo "사용법: ./scripts/release.sh <x.y.z> 'English summary' '한국어 해요체 본문' [Gradle 인자...]" >&2
    exit 1
fi
next="$1"; summary="$2"; body="$3"; shift 3
[[ "$next" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || { echo "버전은 x.y.z 형식으로 써 주세요." >&2; exit 1; }
[[ "$summary" != *$'\n'* ]] && (( ${#summary} <= 72 )) && [[ "$summary" != *'.' ]] \
    && LC_ALL=C grep -Eq '^(feat|fix|refactor|docs|test|chore|style|perf|build|ci|revert)(\([a-z0-9._/-]+\))?: [a-z0-9][ -~]*$' <<< "$summary" \
    || { echo "제목은 72자 이내의 'type: 영어 요약' 형식(Conventional Commits)으로 써 주세요. gitmoji는 쓰지 않아요." >&2; exit 1; }
[[ "$body" =~ 요([.!?]|…)*[[:space:]]*$ ]] || { echo "본문은 한국어 해요체로 써 주세요." >&2; exit 1; }
for tool in git gh python3; do command -v "$tool" >/dev/null || { echo "필수 도구가 없어요: $tool" >&2; exit 1; }; done
gh auth status >/dev/null 2>&1 || { echo "먼저 gh auth login으로 인증해 주세요." >&2; exit 1; }
branch="$(git symbolic-ref --quiet --short HEAD)"
tag="v$next"
if git show-ref --verify --quiet "refs/tags/$tag"; then echo "이미 있는 태그예요: $tag" >&2; exit 1; fi
git fetch origin "$branch" --quiet
[[ "$(git rev-parse HEAD)" == "$(git rev-parse FETCH_HEAD)" ]] || { echo "로컬 브랜치와 원격 브랜치를 먼저 동기화해 주세요." >&2; exit 1; }
[[ -z "$(git ls-remote origin "refs/tags/$tag")" ]] || { echo "원격에 이미 있는 태그예요: $tag" >&2; exit 1; }
previous="$(cat VERSION)"
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
name="logent-custom-openai"
# 변경 사항과 VERSION 변경을 커밋 두 개로 나눠요.
git add -A
git reset --quiet -- VERSION
if git diff --cached --quiet; then echo "VERSION 변경 외에 커밋할 변경 사항이 없어요." >&2; exit 1; fi
git commit -m "$summary" -m "$body"
git add VERSION
git commit -m "chore(release): $next" -m "VERSION을 $next로 올리고 $tag 태그를 만들었어요."
git tag -a "$tag" -m "$tag"$'\n\n'"VERSION을 $next로 올린 릴리스예요."
trap - EXIT
git push --atomic origin "HEAD:refs/heads/$branch" "refs/tags/$tag"
./scripts/publish-release.sh --built "$body"