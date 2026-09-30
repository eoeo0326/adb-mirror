#!/usr/bin/env bash
# 포터블 배포본을 풀어 구조를 확인한다: 실행 파일과 portable 표식이 SettingsLocation 규칙대로 놓였는지, 실행 권한이 있는지.
#   scripts/check-portable.sh composeApp/build/release/ADB-Mirror-0.3.0-linux-x64-portable.tar.gz
set -euo pipefail
archive="$1"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
case "$archive" in
  *.zip)
    # Windows Git Bash에는 unzip이 없을 수 있어 Python으로도 푼다.
    if command -v unzip >/dev/null; then unzip -q "$archive" -d "$work"
    else "$(command -v python3 || command -v python)" -m zipfile -e "$archive" "$work"; fi ;;
  *.tar.gz) tar xzf "$archive" -C "$work" ;;
  *) echo "지원하지 않는 형식: $archive" >&2; exit 1 ;;
esac
root="$work/ADB Mirror"
[ -f "$root/portable" ] || { echo "portable 표식이 없습니다: $root/portable" >&2; exit 1; }
if [ -f "$root/ADB Mirror.exe" ]; then
  echo "Windows 포터블: 실행 파일과 표식이 같은 폴더에 있습니다"
elif [ -f "$root/bin/ADB Mirror" ]; then
  [ -x "$root/bin/ADB Mirror" ] || { echo "실행 권한이 없습니다: bin/ADB Mirror" >&2; exit 1; }
  # jpackage는 번들 런타임에서 bin/java를 빼므로 JVM 라이브러리가 들어 있는지만 본다.
  [ -n "$(find "$root/lib" -name 'libjvm.so' | head -1)" ] || { echo "번들 런타임(libjvm.so)이 없습니다" >&2; exit 1; }
  echo "Linux 포터블: bin/ 위에 표식이 있고 실행 권한이 유지됐습니다"
else
  echo "실행 파일을 찾지 못했습니다" >&2
  ls -R "$root" | head -40 >&2
  exit 1
fi
