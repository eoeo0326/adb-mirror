#!/usr/bin/env bash
# 미러링 중인 KMP Desktop 앱의 CPU를 잰다. 기기 화면을 계속 스크롤해 프레임이 끊이지 않게 하고,
# 0.5초마다 ps로 %CPU(코어 1개 = 100%)를 읽어 평균·최대를 낸다.
#   scripts/measure-cpu.sh <serial> [초=10]
# 앱은 미리 띄워 둔다: ADB_MIRROR_DEV_CONNECT=<serial> ./gradlew :composeApp:run
set -euo pipefail
serial="$1"
seconds="${2:-10}"
pid="$(pgrep -f 'adbmirror.MainKt' | head -1 || true)"
[ -n "$pid" ] || { echo "실행 중인 ADB Mirror가 없습니다" >&2; exit 1; }

# 스크롤 부하: 위아래로 번갈아 민다.
(
  end=$((SECONDS + seconds + 1))
  while [ "$SECONDS" -lt "$end" ]; do
    adb -s "$serial" shell input swipe 540 1600 540 700 300
    adb -s "$serial" shell input swipe 540 700 540 1600 300
  done
) &
load=$!
trap 'kill "$load" 2>/dev/null || true' EXIT

sleep 1
samples=()
n=$((seconds * 2))
i=0
while [ "$i" -lt "$n" ]; do
  samples+=("$(ps -o %cpu= -p "$pid" | tr -d ' ')")
  sleep 0.5
  i=$((i + 1))
done
printf '%s\n' "${samples[@]}" | awk '{ s += $1; if ($1 > m) m = $1 } END { printf "samples=%d avg=%.1f%% max=%.1f%%\n", NR, s / NR, m }'
