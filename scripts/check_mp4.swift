// MP4 파일을 macOS AVFoundation(QuickTime과 같은 엔진)으로 열어 재생 가능 여부·길이·해상도를 출력하고,
// 첫·1초·끝 지점 프레임을 디코딩한다. 두 번째 인자를 주면 1초 지점 프레임을 PNG로 저장한다.
//   swift scripts/check_mp4.swift core/data/build/fixture-mp4/session1.mp4 [frame.png]
import AVFoundation
import AppKit

let url = URL(fileURLWithPath: CommandLine.arguments[1])
let asset = AVURLAsset(url: url)
let sem = DispatchSemaphore(value: 0)
Task {
    do {
        let playable = try await asset.load(.isPlayable)
        let duration = try await asset.load(.duration)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let size = try await tracks.first!.load(.naturalSize)
        let frames = try await tracks.first!.load(.nominalFrameRate)
        print("playable=\(playable) duration=\(String(format: "%.3f", duration.seconds))s size=\(size) nominalFps=\(frames)")
        let gen = AVAssetImageGenerator(asset: asset)
        gen.requestedTimeToleranceBefore = .zero; gen.requestedTimeToleranceAfter = .zero
        for t in [0.0, 1.0, duration.seconds - 0.05] {
            let (img, actual) = try await gen.image(at: CMTime(seconds: t, preferredTimescale: 600))
            print("frame@\(String(format: "%.2f", t)) -> actual \(String(format: "%.3f", actual.seconds)) \(img.width)x\(img.height)")
            if t == 1.0, CommandLine.arguments.count > 2 {
                let rep = NSBitmapImageRep(cgImage: img)
                try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
            }
        }
    } catch { print("ERROR: \(error)") }
    sem.signal()
}
sem.wait()
