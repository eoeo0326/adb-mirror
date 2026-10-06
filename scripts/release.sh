#!/usr/bin/env bash
# develop에서 release/vX.Y.Z를 만들고 appVersion·CHANGELOG를 고친 뒤, main·develop 양쪽에 PR을 연다.
# 두 PR은 merge commit으로 머지한다. main PR이 머지되면 release-tag.yml이 태그를 만들고 릴리즈한다.
#   scripts/release.sh 0.7.0            # 브랜치 push + PR 2개
#   scripts/release.sh 0.7.0 --no-pr    # 로컬 브랜치에 커밋만(push·PR 없음). 결과 확인용
set -euo pipefail

usage() { echo "사용법: scripts/release.sh X.Y.Z [--no-pr]" >&2; exit 2; }
fail() { echo "release: $*" >&2; exit 1; }

[ $# -ge 1 ] || usage
version="$1"
open_pr=true
case "${2:-}" in
  "") ;;
  --no-pr) open_pr=false ;;
  *) usage ;;
esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "버전은 X.Y.Z 형식이어야 합니다: $version"

root="$(git rev-parse --show-toplevel)"
cd "$root"
tag="v$version"
branch="release/$tag"

[ -z "$(git status --porcelain)" ] || fail "작업 트리에 커밋하지 않은 변경이 있습니다"
git fetch --quiet origin develop main --tags
if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null; then fail "태그 $tag 가 이미 있습니다"; fi
if git ls-remote --exit-code --heads origin "$branch" >/dev/null; then fail "브랜치 $branch 가 이미 있습니다"; fi
if git show-ref --verify --quiet "refs/heads/$branch"; then fail "로컬 브랜치 $branch 가 이미 있습니다"; fi

current="$(git show origin/develop:gradle.properties | sed -n 's/^appVersion=//p')"
[ -n "$current" ] || fail "gradle.properties에서 appVersion을 찾지 못했습니다"
[ "$version" != "$current" ] || fail "develop의 appVersion이 이미 $version 입니다"
[ "$(printf '%s\n%s\n' "$current" "$version" | sort -V | tail -1)" = "$version" ] || fail "$version 은 현재 버전 $current 보다 낮습니다"

git switch --quiet -c "$branch" --no-track origin/develop

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

awk -v v="$version" '/^appVersion=/ { print "appVersion=" v; next } { print }' gradle.properties > "$tmp"
cat "$tmp" > gradle.properties

# Unreleased 내용을 새 버전 절로 옮기고, 맨 아래 비교 링크를 새 버전 기준으로 바꾼다.
today="$(TZ=Asia/Seoul date +%F)"
awk -v v="$version" -v d="$today" '
  /^## \[Unreleased\]/ { print; print ""; print "## [" v "] - " d; inside = 1; next }
  /^## \[/ { inside = 0 }
  inside && NF { body = 1 }
  /^\[Unreleased\]: / {
    match($0, /compare\/v[0-9.]+\.\.\.HEAD/)
    prev = substr($0, RSTART + 9, RLENGTH - 9 - 7)
    base = substr($0, 15, RSTART - 15)
    print "[Unreleased]: " base "compare/v" v "...HEAD"
    print "[" v "]: " base "compare/v" prev "...v" v
    links = 1
    next
  }
  { print }
  END {
    if (!body) { print "CHANGELOG의 Unreleased가 비어 있습니다" > "/dev/stderr"; exit 3 }
    if (!links) { print "CHANGELOG에서 [Unreleased] 비교 링크를 찾지 못했습니다" > "/dev/stderr"; exit 4 }
  }
' CHANGELOG.md > "$tmp" || { git checkout --quiet -- .; git switch --quiet -; git branch --quiet -D "$branch"; fail "CHANGELOG를 고치지 못했습니다"; }
cat "$tmp" > CHANGELOG.md

git add gradle.properties CHANGELOG.md
git commit --quiet -m "chore: $version 릴리즈 준비" -m "- appVersion $version
- CHANGELOG: Unreleased를 $version 절로 정리, 비교 링크 추가"
echo "커밋: $(git log -1 --format='%h %s')"

if [ "$open_pr" = false ]; then
  echo "로컬 브랜치 $branch 에 커밋했습니다(push·PR 없음)."
  exit 0
fi

git push --quiet -u origin "$branch"

notes="$(awk -v v="$version" '$0 ~ "^## \\[" v "\\]" {on=1; next} /^## \[/ {if (on) exit} on' CHANGELOG.md)"
main_body="## 📝 요약
$version 릴리즈입니다. 머지하면 \`release-tag.yml\`이 \`$tag\` 태그를 만들고, \`build.yml\`이 설치 파일을 빌드해 GitHub Release에 올립니다.

> **Create a merge commit**으로 머지해 주세요. squash하면 main과 develop의 히스토리가 갈라집니다.
> 같은 브랜치의 develop PR도 함께 머지한 뒤 \`$branch\` 브랜치를 지웁니다.

## 📋 변경 사항 (CHANGELOG)
$notes"
develop_body="## 📝 요약
\`$branch\`의 버전 변경(\`appVersion\`·CHANGELOG)을 develop에 반영합니다.

> **Create a merge commit**으로 머지해 주세요."

main_url="$(gh pr create --base main --head "$branch" --title "[release] $tag" --body "$main_body")"
develop_url="$(gh pr create --base develop --head "$branch" --title "[release] $tag → develop" --body "$develop_body")"
echo "main PR: $main_url"
echo "develop PR: $develop_url"
