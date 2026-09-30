# Changelog

이 프로젝트의 주요 변경 사항을 기록합니다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따릅니다.

## [Unreleased]

### 변경
- Swift 프로토타입을 `macos-swift/`로 이동 (실행: `cd macos-swift && ./run.sh`)

### 추가
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

[Unreleased]: https://github.com/eoeo0326/adb-mirror/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/eoeo0326/adb-mirror/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/eoeo0326/adb-mirror/releases/tag/v0.1.0
