import Foundation

/// scrcpy 컨트롤 소켓. 클라이언트 → 기기 방향으로 입력 이벤트를 보낸다.
final class ControlChannel {
    enum TouchAction: UInt8 {
        case down = 0 // MotionEvent.ACTION_DOWN
        case up = 1   // MotionEvent.ACTION_UP
        case move = 2 // MotionEvent.ACTION_MOVE
    }

    private static let typeInjectTouch: UInt8 = 2
    /// POINTER_ID_GENERIC_FINGER: 서버가 마우스가 아닌 손가락 터치로 주입한다.
    private static let pointerIdFinger: Int64 = -2

    private let fd: Int32
    private let queue = DispatchQueue(label: "adb-mirror.control")

    init(fd: Int32) {
        self.fd = fd
        // 기기 → 클라이언트 메시지(클립보드 등)는 쓰지 않지만, 소켓 버퍼가 차지 않게 비워 둔다.
        Thread.detachNewThread {
            var buf = [UInt8](repeating: 0, count: 4096)
            while recv(fd, &buf, buf.count, 0) > 0 {}
        }
    }

    /// 좌표는 현재 영상 프레임 기준. 서버는 screen 크기가 현재 영상 크기와 다르면 이벤트를 버린다.
    func sendTouch(_ action: TouchAction, x: Int, y: Int, screenWidth: Int, screenHeight: Int) {
        var msg = Data(capacity: 32)
        msg.append(Self.typeInjectTouch)
        msg.append(action.rawValue)
        msg.appendBE(UInt64(bitPattern: Self.pointerIdFinger))
        msg.appendBE(UInt32(clamping: x))
        msg.appendBE(UInt32(clamping: y))
        msg.appendBE(UInt16(clamping: screenWidth))
        msg.appendBE(UInt16(clamping: screenHeight))
        msg.appendBE(UInt16(action == .up ? 0 : 0xFFFF)) // pressure (u16 고정소수점, 0xFFFF = 1.0)
        msg.appendBE(UInt32(0)) // action button
        msg.appendBE(UInt32(0)) // buttons
        send(msg)
    }

    private func send(_ data: Data) {
        queue.async { [fd] in
            data.withUnsafeBytes { buf in
                var offset = 0
                while offset < buf.count {
                    let n = Darwin.send(fd, buf.baseAddress! + offset, buf.count - offset, 0)
                    if n <= 0 { return }
                    offset += n
                }
            }
        }
    }
}

extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ value: T) {
        var be = value.bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
    }
}
