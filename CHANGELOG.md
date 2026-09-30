# Changelog

이 프로젝트의 주요 변경 사항을 기록합니다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따릅니다.

## [Unreleased]

### 추가
- 앱 아이콘(겹친 두 폰 + 얇은 비침): SVG 원본, PNG 16~1024px, `.icns`, `.ico`
- 실행 중 Dock 아이콘 표시

## [0.1.0] - 2026-09-30

macOS용 Swift 프로토타입 첫 릴리즈입니다.

### 추가
- scrcpy-server v4.1 기반 저지연 화면 미러링 (VideoToolbox 디코딩, 회전 대응)
- 마우스 클릭·드래그를 기기 터치로 전달, `--view-only` 보기 전용 모드
- 스크린샷 클립보드 복사(⌘C)·파일 저장(⌘S), `--screenshot-dir`
- `--stats` 표시 fps 출력
- 실행 스크립트 `run.sh`, `ADB Mirror.command`

[Unreleased]: https://github.com/eoeo0326/adb-mirror/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/eoeo0326/adb-mirror/releases/tag/v0.1.0
