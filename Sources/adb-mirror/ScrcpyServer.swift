import Foundation

/// 기기에 scrcpy-server를 올리고 forward 터널로 영상 전용 서버를 띄운다.
final class ScrcpyServer {
    /// scripts/fetch-server.sh 의 VERSION 과 반드시 같아야 한다.
    static let serverVersion = "4.1"
    private static let devicePath = "/data/local/tmp/scrcpy-server.jar"

    let adb: Adb
    let serial: String
    private(set) var localPort: UInt16 = 0
    private let scid = String(format: "%08x", UInt32.random(in: 0..<0x8000_0000))
    private var process: Process?
    private var forwardSpec: String?

    /// 서버 프로세스가 스스로 종료됐을 때 호출된다 (USB 분리 등).
    var onExit: ((Int32) -> Void)?

    init(adb: Adb, serial: String) {
        self.adb = adb
        self.serial = serial
    }

    func start(maxSize: Int, maxFps: Int) throws {
        guard let jar = Bundle.module.path(forResource: "scrcpy-server", ofType: nil) else {
            throw AdbError(description: "번들에 scrcpy-server가 없습니다. scripts/fetch-server.sh 를 먼저 실행하세요.")
        }
        try adb.run(["-s", serial, "push", jar, Self.devicePath])

        // tcp:0 을 주면 adb가 빈 포트를 골라 stdout으로 알려준다.
        let socketName = "localabstract:scrcpy_\(scid)"
        let portOut = try adb.run(["-s", serial, "forward", "tcp:0", socketName])
        guard let port = UInt16(portOut.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw AdbError(description: "adb forward 포트 파싱 실패: \(portOut)")
        }
        localPort = port
        forwardSpec = "tcp:\(port)"

        let serverArgs = [
            "scid=\(scid)",
            "tunnel_forward=true",
            "video=true",
            "audio=false",
            "control=false",
            "video_codec=h264",
            "max_size=\(maxSize)",
            "max_fps=\(maxFps)",
            "cleanup=true",
            "log_level=info",
        ]
        let p = Process()
        p.executableURL = URL(fileURLWithPath: adb.path)
        p.arguments = ["-s", serial, "shell",
                       "CLASSPATH=\(Self.devicePath)",
                       "app_process", "/", "com.genymobile.scrcpy.Server", Self.serverVersion] + serverArgs
        // 서버 로그는 그대로 터미널에 흘린다.
        p.standardOutput = FileHandle.standardError
        p.standardError = FileHandle.standardError
        p.terminationHandler = { [weak self] proc in
            self?.onExit?(proc.terminationStatus)
        }
        try p.run()
        process = p
    }

    func stop() {
        if let p = process {
            p.terminationHandler = nil
            if p.isRunning { p.terminate() }
            process = nil
        }
        if let spec = forwardSpec {
            _ = try? adb.run(["-s", serial, "forward", "--remove", spec])
            forwardSpec = nil
        }
    }
}
