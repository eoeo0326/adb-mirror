// 애니메이션 GIF·WebP를 macOS ImageIO로 열어 프레임 수, 프레임별 지연, 가운데 픽셀 색을 출력한다.
//   swift scripts/check_animated_image.swift core/data/build/fixture-webp/solid-3frames.webp
import CoreGraphics
import Foundation
import ImageIO

let url = URL(fileURLWithPath: CommandLine.arguments[1]) as CFURL
guard let src = CGImageSourceCreateWithURL(url, nil) else { print("ERROR: 열 수 없음"); exit(1) }
let count = CGImageSourceGetCount(src)
print("type=\(CGImageSourceGetType(src) as String? ?? "?") frames=\(count)")
for i in 0..<count {
    let props = CGImageSourceCopyPropertiesAtIndex(src, i, nil) as? [CFString: Any] ?? [:]
    let dict = (props[kCGImagePropertyWebPDictionary] ?? props[kCGImagePropertyGIFDictionary]) as? [CFString: Any] ?? [:]
    let delay = dict[kCGImagePropertyWebPUnclampedDelayTime] ?? dict[kCGImagePropertyGIFUnclampedDelayTime] ?? "?"
    guard let img = CGImageSourceCreateImageAtIndex(src, i, nil) else { print("frame \(i): 디코딩 실패"); continue }
    var px = [UInt8](repeating: 0, count: 4)
    let ctx = CGContext(data: &px, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.draw(img, in: CGRect(x: -img.width / 2, y: -img.height / 2, width: img.width, height: img.height))
    print(String(format: "frame %d: %dx%d delay=%@ center=#%02X%02X%02X", i, img.width, img.height, "\(delay)", px[0], px[1], px[2]))
}
