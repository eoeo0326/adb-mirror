#!/usr/bin/env bash
# Icon Composer 원본(assets/icon/AppIcon.icon)을 macOS 26 이상이 쓰는 Assets.car로 컴파일한다.
# Xcode 26의 actool이 필요하다. 결과물은 커밋해 두므로 빌드·CI에는 Xcode가 필요 없다.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/assets/icon/AppIcon.icon"
dest="$root/packaging/macos/Resources/Assets.car"

if ! xcrun --find actool >/dev/null 2>&1; then
  echo "actool을 찾을 수 없습니다. Xcode 26을 설치하고 'sudo xcode-select -s /Applications/Xcode.app'을 실행하세요." >&2
  exit 1
fi

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

xcrun actool "$src" --compile "$out" \
  --app-icon AppIcon --include-all-app-icons \
  --enable-on-demand-resources NO --development-region en \
  --target-device mac --platform macosx --minimum-deployment-target 11.0 \
  --output-partial-info-plist "$out/partial.plist" \
  --output-format human-readable-text --notices --warnings --errors

icon_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIconName' "$out/partial.plist" 2>/dev/null || true)"
if [ "$icon_name" != "AppIcon" ]; then
  echo "partial plist의 CFBundleIconName이 AppIcon이 아닙니다: '$icon_name'" >&2
  exit 1
fi

mkdir -p "$(dirname "$dest")"
cp "$out/Assets.car" "$dest"
echo "만들었습니다: $dest"
