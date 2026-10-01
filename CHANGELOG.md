# Changelog

이 프로젝트의 주요 변경 사항을 기록합니다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따릅니다.

## [Unreleased]

### 추가
- Web 앱(Chromium): WebUSB로 adb 없이 기기에 붙어 미러링(WebCodecs)·터치, 스크린샷 복사(클립보드)·저장(다운로드), MP4 녹화(OPFS에 쓰고 다운로드). WebUSB가 없는 브라우저에는 안내 화면
- Web 앱을 GitHub Pages(https://eoeo0326.github.io/adb-mirror/)로 배포
- 공통 ADB 와이어 프로토콜(CNXN·AUTH·스트림·sync)과 순수 Kotlin RSA 인증 서명. Web이 이것으로 adbd와 직접 말한다

### 수정
- macOS 26에서 앱 아이콘이 흰 판 위에 작게 보이던 문제. Icon Composer 형식 아이콘(`Assets.car`)을 앱에 넣고, macOS 15 이하는 기존 icns를 그대로 씀

## [0.5.0] - 2026-10-01

Phase 3(Android) 완료 릴리즈입니다. Android 앱이 무선 디버깅으로 다른 기기나 이 폰 자신에 붙어 미러링·터치·스크린샷·녹화·GIF/WebP 변환을 합니다. USB 케이블과 PC가 필요 없습니다. Desktop 설치 파일과 포터블은 이 릴리즈의 첨부 파일에 있습니다.

### 추가
- Android 앱: 무선 디버깅 기기 페어링·연결(Kadb), 앱 adb 키를 백업되지 않는 앱 저장소에 보관해 한 번 페어링하면 다시 연결만 하면 됨
- Android 앱 미러링: MediaCodec 하드웨어 디코더가 SurfaceView에 바로 그림, 영상 위 터치를 기기로 전달, 앱을 나갔다 오면 key frame을 다시 받아 이어서 표시
- Android 앱 스크린샷·녹화·변환: 스크린샷은 클립보드 복사와 `Pictures/ADB Mirror` 저장, 녹화는 `Movies/ADB Mirror`, GIF·WebP 변환(MediaCodec 디코더·내장 WebP 인코더)은 `Pictures/ADB Mirror`. 저장소 권한이 필요 없음(Android 9 이하는 앱 전용 폴더)
- Android 앱 무선 기기 찾기: 같은 네트워크의 무선 디버깅 기기를 mDNS로 찾아 목록에서 눌러 연결, 페어링 창을 연 기기의 페어링 포트를 자동으로 채움
- Android 앱이 연결했던 무선 기기를 기억해 다음 실행 때 다시 연결(무선 디버깅을 다시 켜 포트가 바뀌어도 찾아서 연결), "끊기"를 누르면 잊음
- Android 앱으로 이 폰 자신 페어링: "알림으로 이 폰 페어링"을 누르면 개발자 옵션이 열리고, 페어링 창의 코드를 알림 답장으로 입력하면 페어링·연결까지 함(설정 앱을 떠나지 않아 페어링 창이 닫히지 않음)
- Android 앱 런처 아이콘(적응형, Android 13+ 테마 아이콘)과 README의 Android 사용법

### 수정
- 앱이 강제 종료되거나 Desktop 앱이 비정상 종료돼 기기에 남은 scrcpy 서버를, 다음에 같은 기기에 연결할 때 정리함(띄운 서버의 scid를 앱 저장소에 기록)

### 알려진 제한
- Android 앱 설치 파일(APK)은 이 릴리즈에 없습니다. `./gradlew :androidApp:installDebug`로 빌드해 설치합니다
- 앱에서 녹화한 MP4(fragmented)는 갤러리에 길이가 0으로 보입니다(재생은 됨, Desktop 녹화도 같음)
- macOS 앱은 아직 Apple 개발자 서명·공증 전이라 처음 열 때 Finder에서 우클릭 → 열기가 필요합니다
- 1.0 전까지 macOS 패키지 내부 버전은 첫 숫자를 1로 씁니다(jpackage 제한, 이 릴리즈는 1.5.0)
- Web 앱은 Phase 4에서 이어 갑니다

## [0.4.0] - 2026-09-30

Phase 2(Desktop) 완료 릴리즈입니다. KMP Desktop 앱이 Swift 프로토타입의 기능(미러링·터치·스크린샷)을 모두 갖췄고, 녹화·GIF/WebP 변환·설정·설치 파일이 더해졌습니다. macOS(arm64·x64)·Windows·Linux(x64·arm64) 설치 파일과 포터블은 이 릴리즈의 첨부 파일에 있습니다.

### 추가
- 미러링
  - 기기 목록 창에서 기기를 골라(한 대여도 직접 선택) 기기마다 미러링 창을 엶. 창 크기는 영상 비율에 맞추고 회전하면 다시 맞춤, 연결이 끊기면 창 안에서 다시 연결
  - 클릭·드래그 터치, 보기 전용, 클릭 이펙트(파문·드래그 경로), 기기에 터치 표시(show_touches, 끝나면 원래 값으로 복원)
- 스크린샷: 기기 원본 해상도로 클립보드 복사·파일 저장(`adb-mirror_<serial>_<시각>.png`)
- MP4 녹화(⌘R / Ctrl+R): 다시 인코딩하지 않는 fragmented MP4, 시작 시 key frame 즉시 요청, 회전하면 `_part2`…으로 나눔, 끊기거나 창을 닫아도 그때까지 저장
- GIF·WebP 변환: 구간·fps·너비·반복·품질·디더링, 예상 크기와 20MB 초과 경고, 진행률·취소. 회전으로 나뉜 part는 이어서 하나로(다른 방향은 가운데 맞춤)
- 메뉴 막대와 단축키(macOS ⌘ / Windows·Linux Ctrl): 스크린샷 C·S, 녹화 R, 창 닫기 W, 설정 쉼표, 종료 Q
- 설정 창과 설정 저장(해상도·fps·보기 토글·저장 폴더·adb 경로), 창 위치·크기 기억. 설치형은 OS 표준 위치, `portable` 파일이 있으면 옆 `data/`
- 설치 파일: macOS dmg, Windows msi(사용자 단위 설치), Linux deb·rpm, Windows·Linux 포터블(zip·tar.gz)
- GitHub Actions: PR마다 테스트와 5개 OS·아키텍처 패키지 빌드, `v*` 태그 시 Release 업로드와 SHA256SUMS. macOS 서명·공증은 Secrets가 있을 때만

### 성능
- 하드웨어 디코딩(macOS VideoToolbox, Windows D3D11VA·DXVA2, Linux VAAPI, 없으면 소프트웨어)
- 색 변환 너비를 16의 배수로 맞춰 SIMD 경로 사용, YUV 평면을 GPU 셰이더로 그려 CPU 색 변환·복사 제거
- 스크롤 중 CPU(Note10, 1280px): 평균 28.6% → 19.1%, 최대 41.3% → 25.2%. 끄려면 `ADB_MIRROR_HWDECODE=0`·`ADB_MIRROR_GPU_YUV=0`
- GIF: 바뀐 영역만 쓰고 같은 프레임은 합쳐 크기 약 40% 감소

### 알려진 제한
- macOS 앱은 아직 Apple 개발자 서명·공증 전이라 처음 열 때 Finder에서 우클릭 → 열기가 필요합니다
- 1.0 전까지 macOS 패키지 내부 버전은 첫 숫자를 1로 씁니다(jpackage 제한, 이 릴리즈는 1.4.0)
- Android·Web 앱은 Phase 3·4에서 이어 갑니다

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

[Unreleased]: https://github.com/eoeo0326/adb-mirror/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/eoeo0326/adb-mirror/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/eoeo0326/adb-mirror/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/eoeo0326/adb-mirror/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/eoeo0326/adb-mirror/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/eoeo0326/adb-mirror/releases/tag/v0.1.0
