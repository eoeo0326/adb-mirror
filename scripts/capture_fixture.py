#!/usr/bin/env python3
"""scrcpy 영상 소켓 바이트를 그대로 떠서 파서 테스트용 fixture로 저장한다.

저장하는 것은 dummy byte 다음부터의 전체 스트림이다.
구성은 device meta 64바이트 → codec id 4바이트 → 세션·config·프레임 패킷 순서다.
같은 이름의 .json에는 참조 파서로 센 기대값(세션 목록, 패킷 수, 크기)을 남긴다.

사용법:
  ./scripts/fetch-server.sh
  python3 scripts/capture_fixture.py -s <serial> --name scrcpy-v4.1-h264-rotate --seconds 8 --rotate

--rotate를 주면 캡처 도중 가로로 돌렸다가 되돌린다. 기기의 회전 설정은 끝나면 원래 값으로 복원한다.
공개 레포에 들어가므로 개인정보가 없는 화면에서 캡처하고, 함께 저장되는 스크린샷으로 확인할 것.
"""
import argparse
import json
import os
import random
import socket
import struct
import subprocess
import sys
import threading
import time

SERVER_VERSION = "4.1"  # Sources/adb-mirror/ScrcpyServer.swift 의 serverVersion 과 같아야 한다
DEVICE_PATH = "/data/local/tmp/scrcpy-server.jar"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER_JAR = os.path.join(ROOT, "Sources/adb-mirror/Resources/scrcpy-server")

FLAG_SESSION = 1 << 63
FLAG_CONFIG = 1 << 62
FLAG_KEY = 1 << 61


def adb(serial, *args, check=True):
    out = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True)
    if check and out.returncode != 0:
        sys.exit(f"adb {' '.join(args)} 실패: {out.stderr or out.stdout}")
    return out.stdout.strip()


def parse(data):
    """참조 파서. Swift StreamReader 와 같은 규칙으로 스트림을 센다."""
    pos = 0

    def take(n):
        nonlocal pos
        if pos + n > len(data):
            raise EOFError
        chunk = data[pos:pos + n]
        pos += n
        return chunk

    name = take(64).split(b"\0", 1)[0].decode("utf-8", "replace")
    codec = struct.unpack(">I", take(4))[0]
    sessions, configs, frames, keys, payload = [], 0, 0, 0, 0
    first_pts = last_pts = None
    try:
        while True:
            header = take(12)
            first = struct.unpack(">Q", header[:8])[0]
            if first & FLAG_SESSION:
                w, h = struct.unpack(">II", header[4:])
                sessions.append({"width": w, "height": h, "frameIndex": frames})
                continue
            size = struct.unpack(">I", header[8:])[0]
            take(size)
            payload += size
            if first & FLAG_CONFIG:
                configs += 1
            else:
                frames += 1
                keys += 1 if first & FLAG_KEY else 0
                pts = first & ((1 << 61) - 1)
                first_pts = pts if first_pts is None else first_pts
                last_pts = pts
    except EOFError:
        pass
    return {
        "deviceNameLength": len(name),
        "codecId": f"0x{codec:08x}",
        "sessions": sessions,
        "configPackets": configs,
        "framePackets": frames,
        "keyFrames": keys,
        "payloadBytes": payload,
        "firstPtsUs": first_pts,
        "lastPtsUs": last_pts,
        "trailingBytes": len(data) - pos,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--seconds", type=float, default=8)
    ap.add_argument("--max-size", type=int, default=720)
    ap.add_argument("--rotate", action="store_true")
    args = ap.parse_args()

    if not os.path.isfile(SERVER_JAR):
        sys.exit("scrcpy-server가 없습니다. scripts/fetch-server.sh 를 먼저 실행하세요.")

    serial = args.serial
    scid = f"{random.randrange(1 << 31):08x}"
    adb(serial, "push", SERVER_JAR, DEVICE_PATH)
    port = int(adb(serial, "forward", "tcp:0", f"localabstract:scrcpy_{scid}"))
    server = subprocess.Popen(
        ["adb", "-s", serial, "shell", f"CLASSPATH={DEVICE_PATH}", "app_process", "/",
         "com.genymobile.scrcpy.Server", SERVER_VERSION, f"scid={scid}", "tunnel_forward=true",
         "video=true", "audio=false", "control=false", "video_codec=h264",
         f"max_size={args.max_size}", "max_fps=30", "cleanup=true", "log_level=warn"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    saved = {k: adb(serial, "shell", "settings", "get", "system", k)
             for k in ("accelerometer_rotation", "user_rotation")}
    out_dir = os.path.join(ROOT, "fixtures")
    shots = []
    try:
        sock = None
        for _ in range(100):  # forward 터널은 listen 전에도 connect 가 되므로 dummy byte 로 확인
            s = socket.create_connection(("127.0.0.1", port))
            if s.recv(1):
                sock = s
                break
            s.close()
            time.sleep(0.1)
        if sock is None:
            sys.exit("scrcpy 서버에 연결하지 못했습니다.")

        chunks = []

        def reader():
            while True:
                b = sock.recv(65536)
                if not b:
                    return
                chunks.append(b)

        t = threading.Thread(target=reader, daemon=True)
        t.start()
        t_start = time.time()

        def shot(tag):
            path = os.path.join(out_dir, f"{args.name}.{tag}.png")
            with open(path, "wb") as f:
                f.write(subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"],
                                       capture_output=True).stdout)
            shots.append(path)

        time.sleep(1.5)
        shot("start")
        adb(serial, "shell", "input", "swipe", "540", "1600", "540", "900", "400")
        if args.rotate:
            time.sleep(1.5)
            adb(serial, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
            adb(serial, "shell", "settings", "put", "system", "user_rotation", "1")
            time.sleep(2)
            shot("landscape")
            adb(serial, "shell", "settings", "put", "system", "user_rotation", "0")
        remaining = args.seconds - (time.time() - t_start)
        time.sleep(max(remaining, 1))
        sock.shutdown(socket.SHUT_RDWR)
        sock.close()
        t.join(2)
    finally:
        for k, v in saved.items():
            adb(serial, "shell", "settings", "put", "system", k, v, check=False)
        server.terminate()
        adb(serial, "forward", "--remove", f"tcp:{port}", check=False)

    data = b"".join(chunks)
    bin_path = os.path.join(out_dir, f"{args.name}.bin")
    with open(bin_path, "wb") as f:
        f.write(data)
    summary = parse(data)
    summary.update({"serverVersion": SERVER_VERSION, "maxSize": args.max_size, "bytes": len(data)})
    with open(os.path.join(out_dir, f"{args.name}.json"), "w") as f:
        json.dump(summary, f, indent=2)
        f.write("\n")
    print(json.dumps(summary, indent=2))
    print("확인할 스크린샷:", *shots, sep="\n  ")


if __name__ == "__main__":
    main()
