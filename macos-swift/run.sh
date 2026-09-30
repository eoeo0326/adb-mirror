#!/usr/bin/env bash
# adb-mirror 실행 스크립트. 필요하면 scrcpy-server 다운로드와 빌드를 먼저 한 뒤 실행한다.
# 사용법: ./run.sh [adb-mirror 옵션...]   예) ./run.sh -s R3CM90LKDDJ --view-only
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f Sources/adb-mirror/Resources/scrcpy-server ]; then
    ../scripts/fetch-server.sh
fi

# 증분 빌드라 변경이 없으면 1초 안에 끝난다.
swift build -c release 2>&1 | tail -1

exec .build/release/adb-mirror "$@"
