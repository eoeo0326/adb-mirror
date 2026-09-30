# fixtures

파서·muxer 테스트에 쓰는 실제 scrcpy 스트림입니다. `scripts/capture_fixture.py`로 캡처했습니다.

## scrcpy-v4.1-h264-rotate

| 파일 | 내용 |
|---|---|
| `.bin` | 영상 소켓에서 dummy byte 다음부터 받은 바이트 전체 (device meta 64B → codec id 4B → 패킷) |
| `.json` | 참조 파서(`capture_fixture.py`의 `parse`)로 센 기대값 |
| `.start.png`, `.landscape.png` | 캡처 중 찍은 기기 화면. 스트림에 개인정보가 없는지 확인하는 용도 |

- 서버 v4.1, H.264, `max_size=720`, `max_fps=30`, 약 8초
- 캡처 도중 가로로 돌렸다가 되돌렸으므로 세션이 3개입니다(340×720 → 720×340 → 340×720). `frameIndex`는 그 세션이 시작되기 전까지 받은 프레임 수입니다.
- 화면은 Android 설정의 "디스플레이" 페이지이고, 상태바는 데모 모드(12:00, 알림 숨김)입니다.

다시 캡처하려면 개인정보가 없는 화면을 띄운 뒤 다음을 실행합니다.

```bash
./scripts/fetch-server.sh
python3 scripts/capture_fixture.py -s <serial> --name scrcpy-v4.1-h264-rotate --seconds 8 --rotate
```

scrcpy 버전을 올리면 fixture도 새 버전으로 다시 떠야 합니다(프로토콜이 버전 간 호환되지 않음).
