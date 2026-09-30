import AppKit
import AVFoundation

/// 영상을 표시하고, 마우스 입력을 영상 좌표계의 터치로 바꿔 넘기는 뷰.
final class MirrorView: NSView {
    let displayLayer = AVSampleBufferDisplayLayer()
    /// 현재 캡처 세션의 영상 크기. 터치 좌표 변환과 서버 전송에 쓴다.
    var videoSize = CGSize.zero
    /// (action, 영상 x, 영상 y). nil이면 보기 전용.
    var onTouch: ((ControlChannel.TouchAction, Int, Int) -> Void)?
    private var isTouching = false

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
        displayLayer.frame = bounds
        displayLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        layer?.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    // 비활성 창에서도 첫 클릭을 터치로 전달.
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) {
        guard let p = videoPoint(event, clamp: false) else { return }
        isTouching = true
        onTouch?(.down, p.x, p.y)
    }

    override func mouseDragged(with event: NSEvent) {
        guard isTouching, let p = videoPoint(event, clamp: true) else { return }
        onTouch?(.move, p.x, p.y)
    }

    override func mouseUp(with event: NSEvent) {
        guard isTouching, let p = videoPoint(event, clamp: true) else { return }
        isTouching = false
        onTouch?(.up, p.x, p.y)
    }

    /// 뷰 좌표 → 영상 픽셀 좌표. 레터박스 영역 클릭은 clamp=false면 무시한다.
    private func videoPoint(_ event: NSEvent, clamp: Bool) -> (x: Int, y: Int)? {
        guard videoSize.width > 0, videoSize.height > 0 else { return nil }
        let rect = AVMakeRect(aspectRatio: videoSize, insideRect: bounds)
        let p = convert(event.locationInWindow, from: nil)
        guard clamp || rect.contains(p) else { return nil }

        let nx = min(max((p.x - rect.minX) / rect.width, 0), 1)
        let ny = min(max((rect.maxY - p.y) / rect.height, 0), 1) // AppKit은 y가 아래→위
        let x = min(Int(nx * videoSize.width), Int(videoSize.width) - 1)
        let y = min(Int(ny * videoSize.height), Int(videoSize.height) - 1)
        return (x, y)
    }
}

final class MirrorWindow: NSWindow {
    let mirrorView = MirrorView(frame: NSRect(x: 0, y: 0, width: 360, height: 780))
    var displayLayer: AVSampleBufferDisplayLayer { mirrorView.displayLayer }

    init(title: String) {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 360, height: 780),
                   styleMask: [.titled, .closable, .miniaturizable, .resizable],
                   backing: .buffered, defer: false)
        self.title = title
        isReleasedWhenClosed = false
        contentView = mirrorView
        center()
    }

    /// 새 캡처 세션 크기에 맞춰 창 비율을 맞춘다. 화면의 85%를 넘지 않게 축소.
    func fit(videoWidth: Int, videoHeight: Int) {
        guard videoWidth > 0, videoHeight > 0 else { return }
        mirrorView.videoSize = CGSize(width: videoWidth, height: videoHeight)
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
