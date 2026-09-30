<p align="center"><img src="assets/icon/png/icon_256.png" width="128" alt="ADB Mirror 아이콘"></p>

# ADB Mirror

adb로 연결한 Android 기기 화면을 데스크톱 창에 띄우고, 마우스로 조작하고, 스크린샷을 찍는 도구입니다.

기기 쪽 인코더로 [scrcpy](https://github.com/Genymobile/scrcpy)의 `scrcpy-server`만 사용합니다. 받은 H.264 영상은 macOS의 VideoToolbox로 직접 디코딩해 표시하므로 scrcpy나 FFmpeg를 따로 설치할 필요가 없습니다.

> 지금 쓸 수 있는 앱은 `macos-swift/`의 macOS용 Swift 프로토타입입니다. Kotlin Multiplatform + Compose Multiplatform으로 옮겨 macOS·Windows·Linux·Android·Web(Chromium)에서 쓰도록 확장할 계획입니다. 자세한 내용은 [로드맵](docs/ROADMAP.md)을 참고하세요.

## 기능

- **미러링:** 최대 60fps로 저지연 미러링을 합니다. 기기를 회전하면 창 비율도 따라 바뀝니다.
- **터치:** 클릭은 탭으로, 드래그는 스와이프로 기기에 전달됩니다. `--view-only`를 주면 보기만 합니다.
- **스크린샷:** 기기 원본 해상도 PNG로 찍습니다. ⌘C로 클립보드에 복사하고, ⌘S로 파일에 저장합니다.
- **정리:** 창을 닫으면 adb forward와 기기 쪽 서버를 자동으로 정리합니다.

## 요구사항

- macOS 13 이상, Xcode Command Line Tools(`swift`)
- adb: `PATH`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `~/Library/Android/sdk` 순서로 찾습니다.
- Android 5.0 이상 기기에서 USB 디버깅이 켜져 있어야 합니다.

## 실행 (macOS Swift 프로토타입)

```bash
git clone https://github.com/eoeo0326/adb-mirror.git
cd adb-mirror/macos-swift
./run.sh                          # 연결된 첫 기기
./run.sh -s <serial> --view-only  # 기기 지정, 보기 전용
```

`run.sh`는 처음 실행할 때 `scrcpy-server`를 받아 sha256을 검증하고, 빌드한 뒤 실행합니다. Finder에서 `macos-swift/ADB Mirror.command`를 더블클릭해도 됩니다.

수동으로 빌드하려면 레포 루트에서 다음과 같이 합니다.

```bash
./scripts/fetch-server.sh
cd macos-swift
swift build -c release
.build/release/adb-mirror
```

## KMP 앱 (개발 중)

Kotlin Multiplatform + Compose Multiplatform 버전입니다. 지금은 Desktop에서 기기 선택 → 미러링 → 터치까지 됩니다(Android·Web은 준비 중). JDK 21과 Android SDK(compileSdk 37)가 필요하고, scrcpy-server는 빌드할 때 Gradle이 받아 옵니다.

```bash
./gradlew :composeApp:run                         # Desktop
./gradlew :androidApp:installDebug                # Android
./gradlew :composeApp:wasmJsBrowserDevelopmentRun # Web
./gradlew allTests                                # 공통 테스트
./gradlew :core:data:jvmTest -Padbmirror.device=<serial>  # 실기기 통합 테스트
ADB_MIRROR_STATS=1 ./gradlew :composeApp:run      # 초당 디코딩 프레임 수 출력
```

## 구조

```
core/domain      모델 · Repository 인터페이스 · UseCase (순수 Kotlin)
core/adb         AdbTransport 인터페이스와 플랫폼별 구현
core/data        scrcpy 프로토콜 · Repository 구현
feature/mirror   MVI 화면 (Compose)
composeApp       공유 앱 + Desktop · Web 진입점
androidApp       Android 앱 진입점
macos-swift/     Swift 프로토타입 (KMP 버전이 같은 기능을 갖출 때까지 유지)
scripts/         scrcpy-server 다운로드, fixture 캡처
fixtures/        파서 테스트용 실제 스트림
```

## 옵션

| 옵션 | 설명 |
|---|---|
| `-s, --serial SERIAL` | 대상 기기 (기본: 연결된 첫 기기) |
| `--max-size PX` | 긴 변 최대 픽셀 (기본 1280, 0이면 원본) |
| `--fps N` | 최대 프레임레이트 (기본 60) |
| `--view-only` | 터치를 보내지 않음 |
| `--screenshot-dir DIR` | ⌘S 저장 폴더 (기본 `~/Desktop`) |
| `--stats` | 1초마다 표시 fps와 디스플레이 레이어 상태 출력 |

## 단축키

| 키 | 동작 |
|---|---|
| ⌘C | 스크린샷을 클립보드에 PNG로 복사 |
| ⌘S | 스크린샷을 `<serial>_yyyyMMdd_HHmmss.png`로 저장 |
| ⌘Q | 종료 (창 닫기, Ctrl+C도 같음) |

## 동작 원리

```
[Android]  scrcpy-server (MediaCodec H.264 인코딩)
     │  localabstract:scrcpy_<scid>
     │  adb forward tcp:<port>
[Mac]      영상 소켓 → scrcpy v4 프로토콜 파싱 → Annex B→AVCC → AVSampleBufferDisplayLayer
           컨트롤 소켓 ← 마우스 이벤트를 INJECT_TOUCH_EVENT로 직렬화
           adb exec-out screencap -p → 스크린샷 PNG
```

- scrcpy는 화면이 바뀔 때만 프레임을 보냅니다. 그래서 정지 화면에서는 `--stats`가 fps=0으로 나오는 것이 정상입니다.
- 서버와 클라이언트 프로토콜은 버전 간 호환되지 않습니다. `scripts/fetch-server.sh`의 `VERSION`과 `macos-swift/Sources/adb-mirror/ScrcpyServer.swift`의 `serverVersion`은 항상 함께 바꿔야 합니다.

## 로드맵

KMP/CMP로 옮기는 작업은 Phase 0~4로 나눠 [GitHub Milestones](https://github.com/eoeo0326/adb-mirror/milestones)에서 관리합니다. 요약은 [docs/ROADMAP.md](docs/ROADMAP.md)에 있습니다.

## 아이콘

`assets/icon/`에 원본 SVG와 여러 크기의 PNG(16~1024px), macOS용 `AppIcon.icns`, Windows용 `icon.ico`가 있습니다. 겹친 두 폰은 원본과 미러링된 화면을, 아래의 옅은 비침은 거울을 나타냅니다.

## 라이선스

[Apache License 2.0](LICENSE). 이 레포에는 `scrcpy-server` 바이너리가 들어 있지 않습니다. 빌드할 때 Genymobile/scrcpy 릴리즈에서 받아 씁니다. 고지 사항은 [NOTICE](NOTICE)를 참고하세요.
