import Foundation

/// scrcpy 영상 소켓에서 읽어 올린 이벤트.
enum StreamEvent {
    case deviceName(String)
    /// 캡처 세션 시작(최초 연결·회전 시). 영상 크기가 바뀐다.
    case session(width: Int, height: Int)
    case config(Data)
    case frame(Data, isKeyFrame: Bool)
}

/// localhost forward 포트에 붙어 scrcpy v4 영상 프로토콜을 파싱한다.
/// 블로킹 소켓을 전용 스레드에서 돌린다.
final class StreamReader {
    private static let h264CodecId: UInt32 = 0x6832_3634 // "h264"
    private static let flagSession: UInt64 = 1 << 63
    private static let flagConfig: UInt64 = 1 << 62
    private static let flagKeyFrame: UInt64 = 1 << 61

    private let port: UInt16
    private var fd: Int32 = -1

    init(port: UInt16) {
        self.port = port
    }

    /// 연결부터 스트림 종료까지 블로킹. 이벤트는 호출 스레드에서 전달된다.
    func run(onEvent: (StreamEvent) -> Void) throws {
        try connectWithRetry()

        let nameBytes = try read(64)
        let name = String(decoding: nameBytes.prefix(while: { $0 != 0 }), as: UTF8.self)
        onEvent(.deviceName(name))

        let codec = try read(4).beUInt32(at: 0)
        guard codec == Self.h264CodecId else {
            throw AdbError(description: String(format: "지원하지 않는 코덱 id 0x%08x", codec))
        }

        while true {
            let header = try read(12)
            let first = header.beUInt64(at: 0)
            if first & Self.flagSession != 0 {
                let width = Int(header.beUInt32(at: 4))
                let height = Int(header.beUInt32(at: 8))
                onEvent(.session(width: width, height: height))
                continue
            }
            let size = Int(header.beUInt32(at: 8))
            let payload = try read(size)
            if first & Self.flagConfig != 0 {
                onEvent(.config(payload))
            } else {
                onEvent(.frame(payload, isKeyFrame: first & Self.flagKeyFrame != 0))
            }
        }
    }

    func close() {
        if fd >= 0 {
            shutdown(fd, SHUT_RDWR)
            Darwin.close(fd)
            fd = -1
        }
    }

    /// forward 터널은 기기 쪽이 아직 listen 전이어도 connect가 성공하므로,
    /// dummy byte 1개를 받을 때까지 재접속한다.
    private func connectWithRetry() throws {
        for _ in 0..<100 {
            if let s = openSocket() {
                fd = s
                if (try? read(1)) != nil { return }
                close()
            }
            usleep(100_000)
        }
        throw AdbError(description: "scrcpy 서버에 연결하지 못했습니다 (10초 초과).")
    }

    private func openSocket() -> Int32? {
        let s = socket(AF_INET, SOCK_STREAM, 0)
        guard s >= 0 else { return nil }
        var one: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if rc != 0 {
            Darwin.close(s)
            return nil
        }
        return s
    }

    private func read(_ count: Int) throws -> Data {
        var data = Data(count: count)
        var offset = 0
        while offset < count {
            let n = data.withUnsafeMutableBytes { buf in
                recv(fd, buf.baseAddress! + offset, count - offset, 0)
            }
            if n <= 0 {
                throw AdbError(description: "영상 스트림이 끊겼습니다.")
            }
            offset += n
        }
        return data
    }
}

extension Data {
    func beUInt32(at offset: Int) -> UInt32 {
        self[startIndex + offset..<startIndex + offset + 4].reduce(0) { $0 << 8 | UInt32($1) }
    }

    func beUInt64(at offset: Int) -> UInt64 {
        self[startIndex + offset..<startIndex + offset + 8].reduce(0) { $0 << 8 | UInt64($1) }
    }
}
