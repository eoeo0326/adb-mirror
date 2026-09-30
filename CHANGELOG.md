# Changelog

이 프로젝트의 주요 변경 사항을 기록합니다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따릅니다.

## [Unreleased]

### 추가
- Desktop adb 전송(`AdbBinaryTransport`): adb 탐색(설정 → PATH → ANDROID_HOME → OS 기본 SDK), `track-devices` 기기 추적, localabstract 소켓 연결(forward + TCP)
- scrcpy 서버 실행기와 미러링 세션(`ScrcpyServerLauncher`, `ScrcpyMirrorSession`), 기기·미러링 Repository 구현
- Desktop KMP 앱 미러링: 기기 목록에서 기기를 골라 연결하면 FFmpeg(LGPL)로 디코딩해 표시, 클릭·드래그 터치, 보기 전용 토글, 연결 끊기
  - 스크롤 중 약 60fps. CPU는 Swift 프로토타입(하드웨어 디코딩)보다 높아 #41에서 하드웨어 디코딩 적용 예정
- `MirrorViewModel`(Intent → UseCase → Reducer), 창을 닫거나 종료 신호로 끝나도 서버·forward 정리
- Desktop 창 구성: 기기 목록 창은 계속 떠 있고 기기마다 미러링 창을 따로 엶. 이미 열린 기기는 그 창을 앞으로, 창 크기는 영상 비율(화면 85% 이내)에 맞춤, 연결이 끊기면 창 안에서 다시 연결
- 클릭 이펙트: 누른 곳에 파문, 드래그 경로를 영상 위에 표시(레터박스 클릭·보기 전용에서는 표시 안 함). 설정 메뉴(보기 전용·클릭 이펙트·기기에 터치 표시)
- 기기에 터치 표시(show_touches): 켜면 연결된 동안 기기 설정을 켜고, 끄기·연결 끊김·창 닫기에서 원래 값으로 복원
- 스크린샷: 기기 원본 해상도(`screencap`)로 클립보드 복사·파일 저장(기본 바탕화면, `adb-mirror_<serial>_<시각>.png`), 결과는 창 아래 알림으로 표시
- Desktop 메뉴 막대와 단축키(macOS ⌘ / Windows·Linux Ctrl): 스크린샷 복사 C·저장 S, 창 닫기 W, 종료 Q, 보기 토글, 연결 끊기·다시 연결. macOS는 화면 위 메뉴 막대에 붙고 앱 메뉴 종료도 세션을 정리
- 설정 저장(`settings.properties`)과 설정 창(macOS ⌘, · Windows·Linux 파일 > 설정…): 해상도·fps·보기 토글·저장 폴더·adb 경로
  - 설치형은 OS 표준 위치, 실행 파일 옆에 `portable` 파일이 있으면 `data/`(포터블)
  - adb를 못 찾으면 첫 창에서 adb 위치를 지정할 수 있음
- MP4 녹화(⌘R / Ctrl+R): 다시 인코딩하지 않고 fragmented MP4로 기록, 시작 시 key frame 즉시 요청, 회전하면 `_part2`, `_part3`…으로 나눔. 녹화 중 경과 시간 표시, 연결이 끊기거나 창을 닫아도 그때까지 저장
- GIF·WebP 변환: 녹화 저장 알림의 "변환…"·창 메뉴 "최근 녹화 변환…"·파일 > 녹화 파일 변환…에서 구간·fps·너비·반복·품질(WebP)·디더링(GIF)을 골라 변환, 진행률·취소, 예상 크기와 20MB 초과 경고. Desktop은 FFmpeg로 디코딩하고 WebP는 FFmpeg 내장 libwebp로 인코딩
- 설치 파일 설정: macOS dmg(번들 ID·아이콘·개발자 도구 분류), Windows msi(사용자 단위 설치·시작 메뉴·고정 upgradeUuid), Linux deb·rpm(`adb-mirror`, Development 메뉴). 1.0 전 macOS 패키지 버전은 첫 숫자를 1로 씀
- 포터블 배포본(Windows zip·Linux tar.gz, `portable` 표식 포함)과 `packageDistributions` 작업: 설치 파일·포터블을 `build/release/`에 `ADB-Mirror-<버전>-<os>-<arch>` 이름으로 모음
- GitHub Actions: 테스트, macOS arm64·x64 / Windows x64 / Linux x64·arm64 패키지 빌드와 포터블 구조 확인, `v*` 태그 시 Release 업로드와 SHA256SUMS. macOS 서명·공증은 Secrets가 있을 때만
- 창 위치 기억: 기기 목록 창은 위치·크기, 미러링 창은 기기별 위치를 `windows.properties`에 저장해 다음 실행 때 복원(모니터 배치가 바뀌어 잡을 수 없는 위치면 기본 위치)
- 하드웨어 디코딩: macOS VideoToolbox·Windows D3D11VA/DXVA2·Linux VAAPI를 먼저 쓰고 없으면 소프트웨어. 색 변환 너비를 16의 배수로 맞춰 SIMD 경로를 씀. 스크롤 중 CPU 약 30% → 25%(`ADB_MIRROR_HWDECODE=0`으로 끌 수 있음)
- GIF 크기 줄이기: 둘째 프레임부터 보이는 화면과 달라진 사각형만 쓰고, 디코딩 잡음(채널 차이 6 이하)은 바뀌지 않은 것으로 봄, 같은 프레임은 지연 시간만 늘림. fixture 4.4초(340px·15fps) 849KB → 482KB
- 회전으로 나뉜 녹화 이어서 변환: part들을 한 GIF·WebP로 잇고, 방향이 다른 part는 첫 part 크기 캔버스 가운데에 맞춤(남는 곳 검정). 저장 알림·최근 녹화·파일 선택(다른 part도 자동으로 찾음) 모두 적용
- scrcpy-server를 Gradle 작업이 받아 sha256 검증 후 JVM 리소스로 번들 (`scrcpy.version`·`scrcpy.sha256`은 `gradle.properties`)

## [0.3.0] - 2026-09-30

Phase 1(공통 코드) 완료 릴리즈입니다. 사용자가 쓰는 앱은 아직 `macos-swift/`의 Swift 프로토타입이며, KMP 앱은 공통 로직만 갖춘 상태입니다.

### 변경
- Swift 프로토타입을 `macos-swift/`로 이동 (실행: `cd macos-swift && ./run.sh`)

### 추가
- GIF89a 인코더(median cut 256색 팔레트, Floyd–Steinberg 디더링 선택, LZW, 반복 설정)와 애니메이션 WebP 컨테이너 muxer(VP8X·ANIM·ANMF). ImageIO로 디코딩 확인, 검증 스크립트 `scripts/check_animated_image.swift`
- fragmented MP4 muxer(`Mp4Muxer`): H.264를 다시 인코딩하지 않고 ftyp·moov(avcC) + moof·mdat 조각으로 기록. fixture로 만든 MP4를 AVFoundation에서 재생·디코딩 확인, 검증 스크립트 `scripts/check_mp4.swift`
- MVI 계약(`MirrorState`·`MirrorIntent`·`MirrorResult`·`MirrorEffect`)과 순수 Reducer, 상태 전이 테스트
- scrcpy 프로토콜 공통 코드: 영상 스트림 파서(`VideoStreamParser`), 컨트롤 메시지 직렬화(터치·`RESET_VIDEO`), Annex B 분리. 실제 fixture와 참조 파서 결과 대조 테스트
- domain 레이어: 모델(기기·영상·터치·스크린샷·녹화·변환 옵션·설정), Repository 인터페이스 5종, UseCase 13종과 단위 테스트
- KMP/CMP Gradle 멀티모듈 뼈대: `core/domain` · `core/adb` · `core/data` · `feature/mirror` · `composeApp`(Desktop · Web) · `androidApp`

## [0.2.0] - 2026-09-30

Phase 0(기준 데이터) 완료 릴리즈입니다.

### 추가
- 앱 아이콘(겹친 두 폰 + 얇은 비침): SVG 원본, PNG 16~1024px, `.icns`, `.ico`
- 실행 중 Dock 아이콘 표시
- 파서·muxer 테스트용 scrcpy v4.1 영상 스트림 fixture(회전 포함)와 캡처 스크립트 `scripts/capture_fixture.py`
- AI 리뷰 규칙 `AGENTS.md`, 리뷰어 관점 PR 템플릿

## [0.1.0] - 2026-09-30

macOS용 Swift 프로토타입 첫 릴리즈입니다.

### 추가
- scrcpy-server v4.1 기반 저지연 화면 미러링 (VideoToolbox 디코딩, 회전 대응)
- 마우스 클릭·드래그를 기기 터치로 전달, `--view-only` 보기 전용 모드
- 스크린샷 클립보드 복사(⌘C)·파일 저장(⌘S), `--screenshot-dir`
- `--stats` 표시 fps 출력
- 실행 스크립트 `run.sh`, `ADB Mirror.command`

[Unreleased]: https://github.com/eoeo0326/adb-mirror/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/eoeo0326/adb-mirror/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/eoeo0326/adb-mirror/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/eoeo0326/adb-mirror/releases/tag/v0.1.0
