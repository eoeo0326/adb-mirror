# 로드맵

처음 만든 macOS 전용 Swift 프로토타입을 **Kotlin Multiplatform + Compose Multiplatform**으로 옮겨 아래 플랫폼을 지원합니다. Phase 4까지 끝나 Swift 프로토타입은 지웠습니다.

| 호스트 | ADB 전송 | 영상 디코딩 | 배포 |
|---|---|---|---|
| Desktop (macOS · Windows · Linux) | `adb` 바이너리 | FFmpeg → Skia | dmg · msi + 포터블 zip · deb · rpm + tar.gz |
| Android | 무선 디버깅 (Kadb) | MediaCodec | APK |
| Web (Chromium 계열) | WebUSB + 직접 구현한 ADB 프로토콜 | WebCodecs | 정적 호스팅 (HTTPS) |

## 아키텍처

MVI + 클린 아키텍처로 구성합니다.

- `core/domain`: 모델, Repository 인터페이스, UseCase (순수 Kotlin)
- `core/data`: scrcpy 프로토콜 파서, MP4 muxer, GIF · WebP 인코더, Repository 구현
- `core/adb`: `AdbTransport` 인터페이스와 플랫폼별 구현
- `feature/mirror`: MVI Contract · Reducer · ViewModel · Compose 화면, 플랫폼별 `VideoSurface`
- `composeApp`: 플랫폼 진입점과 DI (Koin)

영상 프레임은 MVI State에 넣지 않고 디코더로 곧바로 흘려보냅니다. State에는 연결 상태, 해상도 같은 메타데이터만 둡니다.

## 추가 예정 기능

- 기기 선택 필수: 앱은 항상 기기 목록부터 보여주고, 기기가 하나여도 자동으로 연결하지 않습니다.
- 클릭 지점 이펙트(파문 · 드래그 경로)와 기기 터치 표시(`show_touches`) 옵션
- 영상 녹화: H.264를 다시 인코딩하지 않고 MP4에 담습니다. 녹화 중 회전하면 파일을 나눕니다.
- 녹화 파일을 GIF · 애니메이션 WebP로 변환: 구간 · fps · 너비 · 반복 · 품질을 고를 수 있습니다.

## 단계

| Phase | 내용 | 다음 단계로 넘어가는 조건 |
|---|---|---|
| 0 | 기준 데이터: 실제 scrcpy 스트림을 fixture로 캡처 | fixture 확보 |
| 1 | Gradle 멀티모듈 뼈대, domain, 프로토콜 · muxer · 인코더 이식과 테스트 | `jvmTest` 통과 |
| 2 | Desktop: 기능, 녹화 · 변환, 패키징과 CI | Swift 프로토타입과 기능이 같아짐 |
| 3 | Android 호스트 (무선 디버깅) | 공통 코드를 바꾸지 않고 동작 |
| 4 | Web (Chromium, WebUSB) | — |

각 Phase는 [GitHub Milestone](https://github.com/eoeo0326/adb-mirror/milestones)이고, 세부 작업은 Issue로 관리합니다.
