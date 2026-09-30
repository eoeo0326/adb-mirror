#!/usr/bin/env bash
# scrcpy-server를 내려받아 sha256을 검증한 뒤 SwiftPM 리소스로 저장한다.
# 버전을 바꾸면 macos-swift/Sources/adb-mirror/ScrcpyServer.swift 의 serverVersion 도 같이 바꿔야 한다.
set -euo pipefail

VERSION="4.1"
SHA256="deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae"
URL="https://github.com/Genymobile/scrcpy/releases/download/v${VERSION}/scrcpy-server-v${VERSION}"

cd "$(dirname "$0")/.."
DEST="macos-swift/Sources/adb-mirror/Resources/scrcpy-server"
TMP="${DEST}.download"
mkdir -p "$(dirname "$DEST")"

echo "scrcpy-server v${VERSION} 다운로드 중..."
curl -fL --progress-bar -o "$TMP" "$URL"

ACTUAL="$(shasum -a 256 "$TMP" | awk '{print $1}')"
if [ "$ACTUAL" != "$SHA256" ]; then
    rm -f "$TMP"
    echo "sha256 불일치: expected=$SHA256 actual=$ACTUAL" >&2
    exit 1
fi

mv "$TMP" "$DEST"
echo "저장 완료: $DEST"
