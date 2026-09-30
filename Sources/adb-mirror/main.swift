import AppKit

struct Options {
    var serial: String?
    var maxSize = 1280
    var maxFps = 60

    static func parse(_ args: [String]) -> Options {
        var opts = Options()
        var it = args.makeIterator()
        while let arg = it.next() {
            switch arg {
            case "-s", "--serial":
                opts.serial = it.next()
            case "--max-size":
                opts.maxSize = it.next().flatMap(Int.init) ?? opts.maxSize
            case "--fps", "--max-fps":
                opts.maxFps = it.next().flatMap(Int.init) ?? opts.maxFps
            case "-h", "--help":
                print("""
                사용법: adb-mirror [-s SERIAL] [--max-size PX] [--fps N]
                  -s, --serial   대상 기기 (기본: 연결된 첫 기기)
                  --max-size     긴 변 최대 픽셀 (기본 1280, 0이면 원본)
                  --fps          최대 프레임레이트 (기본 60)
                """)
                exit(0)
            default:
                fail("알 수 없는 인자: \(arg) (--help 참고)")
            }
        }
        return opts
    }
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write("adb-mirror: \(message)\n".data(using: .utf8)!)
    exit(1)
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private let options: Options
    private var server: ScrcpyServer?
    private var reader: StreamReader?
    private var window: MirrorWindow?
    private var sigint: DispatchSourceSignal?

    init(options: Options) {
        self.options = options
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            let adb = try Adb.locate()
            let devices = try adb.onlineDevices()
            let serial: String
            if let wanted = options.serial {
                guard devices.contains(wanted) else {
                    fail("기기 \(wanted)를 찾을 수 없습니다. 연결된 기기: \(devices.isEmpty ? "없음" : devices.joined(separator: ", "))")
                }
                serial = wanted
            } else {
                guard let first = devices.first else { fail("연결된 adb 기기가 없습니다.") }
                if devices.count > 1 {
                    print("여러 기기가 연결돼 있어 \(first)를 사용합니다. 다른 기기는 -s 로 지정하세요: \(devices.joined(separator: ", "))")
                }
                serial = first
            }

            let window = MirrorWindow(title: serial)
            window.delegate = self
            window.makeKeyAndOrderFront(nil)
            self.window = window
            NSApp.activate(ignoringOtherApps: true)

            let server = ScrcpyServer(adb: adb, serial: serial)
            server.onExit = { [weak self] status in
                DispatchQueue.main.async {
                    FileHandle.standardError.write("adb-mirror: scrcpy 서버가 종료됐습니다 (status \(status)).\n".data(using: .utf8)!)
                    self?.shutdown()
                }
            }
            try server.start(maxSize: options.maxSize, maxFps: options.maxFps)
            self.server = server
            startReading(port: server.localPort)
        } catch {
            shutdown(error: error)
        }

        // Ctrl+C 로도 정리 후 종료.
        signal(SIGINT, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
        source.setEventHandler { [weak self] in self?.shutdown() }
        source.resume()
        sigint = source
    }

    private func startReading(port: UInt16) {
        let reader = StreamReader(port: port)
        self.reader = reader
        Thread.detachNewThread { [weak self] in
            let decoder = H264Decoder()
            do {
                try reader.run { event in
                    switch event {
                    case .deviceName(let name):
                        DispatchQueue.main.async { self?.window?.title = name }
                    case .session(let width, let height):
                        DispatchQueue.main.async {
                            self?.window?.resetDecoder()
                            self?.window?.fit(videoWidth: width, videoHeight: height)
                        }
                    case .config(let data):
                        decoder.updateConfig(data)
                    case .frame(let data, _):
                        if let sample = decoder.sampleBuffer(for: data) {
                            DispatchQueue.main.async { self?.window?.enqueue(sample) }
                        }
                    }
                }
            } catch {
                DispatchQueue.main.async { self?.shutdown(error: error) }
            }
        }
    }

    private var isShuttingDown = false

    func shutdown(error: Error? = nil) {
        guard !isShuttingDown else { return }
        isShuttingDown = true
        if let error { FileHandle.standardError.write("adb-mirror: \(error)\n".data(using: .utf8)!) }
        reader?.close()
        server?.stop()
        exit(error == nil ? 0 : 1)
    }

    func windowWillClose(_ notification: Notification) {
        shutdown()
    }
}

let options = Options.parse(Array(CommandLine.arguments.dropFirst()))
let app = NSApplication.shared
app.setActivationPolicy(.regular)
let delegate = AppDelegate(options: options)
app.delegate = delegate
app.run()
