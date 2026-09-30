#!/usr/bin/env bash
# Finder에서 더블클릭해 실행하는 런처. 터미널 창이 열리고 run.sh를 실행한다.
# 창을 닫거나 ⌘Q로 미러링을 끄면 터미널도 곧바로 끝난다. 오류가 나면 메시지를 읽을 수 있게 잠시 멈춘다.
cd "$(dirname "$0")"

if ! ./run.sh "$@"; then
    echo
    read -r -p "오류로 종료됐습니다. Enter를 누르면 창을 닫습니다..." _
fi
