import AppKit
import AVFoundation

/// 영상만 표시하는 창. 마우스·키 입력은 기기로 보내지 않는다.
final class MirrorWindow: NSWindow {
    let displayLayer = AVSampleBufferDisplayLayer()

    init(title: String) {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 360, height: 780),
                   styleMask: [.titled, .closable, .miniaturizable, .resizable],
                   backing: .buffered, defer: false)
        self.title = title
        isReleasedWhenClosed = false

        let view = NSView()
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
        displayLayer.frame = view.bounds
        displayLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        view.layer?.addSublayer(displayLayer)
        contentView = view
        center()
    }

    /// 새 캡처 세션 크기에 맞춰 창 비율을 맞춘다. 화면의 85%를 넘지 않게 축소.
    func fit(videoWidth: Int, videoHeight: Int) {
        guard videoWidth > 0, videoHeight > 0 else { return }
        let visible = (screen ?? NSScreen.main)?.visibleFrame.size ?? NSSize(width: 1440, height: 900)
        let scale = min(1, visible.width * 0.85 / CGFloat(videoWidth), visible.height * 0.85 / CGFloat(videoHeight))
        let size = NSSize(width: CGFloat(videoWidth) * scale, height: CGFloat(videoHeight) * scale)
        contentAspectRatio = NSSize(width: videoWidth, height: videoHeight)

        // 현재 창 중심을 유지하며 크기만 바꾼다.
        let oldFrame = frame
        var newFrame = frameRect(forContentRect: NSRect(origin: .zero, size: size))
        newFrame.origin = NSPoint(x: oldFrame.midX - newFrame.width / 2, y: oldFrame.midY - newFrame.height / 2)
        setFrame(newFrame, display: true, animate: false)
    }

    func enqueue(_ sample: CMSampleBuffer) {
        if displayLayer.status == .failed {
            displayLayer.flush()
        }
        displayLayer.enqueue(sample)
    }

    /// 새 세션 시작 시 이전 세션의 디코더 상태를 버린다.
    func resetDecoder() {
        displayLayer.flushAndRemoveImage()
    }
}
