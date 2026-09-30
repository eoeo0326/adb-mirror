import Foundation

struct AdbError: Error, CustomStringConvertible {
    let description: String
}

/// adb 실행 파일 래퍼. 모든 명령은 지정한 serial 기기를 대상으로 한다.
struct Adb {
    let path: String

    static func locate() throws -> Adb {
        let fm = FileManager.default
        let env = ProcessInfo.processInfo.environment
        var candidates: [String] = []
        if let pathVar = env["PATH"] {
            candidates += pathVar.split(separator: ":").map { "\($0)/adb" }
        }
        for key in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
            if let sdk = env[key] { candidates.append("\(sdk)/platform-tools/adb") }
        }
        candidates.append("\(NSHomeDirectory())/Library/Android/sdk/platform-tools/adb")

        guard let found = candidates.first(where: { fm.isExecutableFile(atPath: $0) }) else {
            throw AdbError(description: "adb를 찾을 수 없습니다. PATH 또는 ANDROID_HOME을 확인하세요.")
        }
        return Adb(path: found)
    }

    /// 명령을 실행하고 끝날 때까지 기다린다. 실패하면 stderr를 담아 throw.
    @discardableResult
    func run(_ args: [String]) throws -> String {
        String(decoding: try runData(args), as: UTF8.self)
    }

    /// 원본 해상도 PNG 스크린샷. 미러링 스트림과 별개로 기기에서 직접 캡처한다.
    func screenshot(serial: String) throws -> Data {
        let png = try runData(["-s", serial, "exec-out", "screencap", "-p"])
        guard png.starts(with: [0x89, 0x50, 0x4E, 0x47]) else {
            throw AdbError(description: "screencap 결과가 PNG가 아닙니다.")
        }
        return png
    }

    func runData(_ args: [String]) throws -> Data {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        let out = Pipe()
        let err = Pipe()
        process.standardOutput = out
        process.standardError = err
        try process.run()
        let outData = out.fileHandleForReading.readDataToEndOfFile()
        let errData = err.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()

        guard process.terminationStatus == 0 else {
            let stderr = String(decoding: errData.isEmpty ? outData : errData, as: UTF8.self)
            throw AdbError(description: "adb \(args.joined(separator: " ")) 실패: \(stderr)")
        }
        return outData
    }

    /// `device` 상태인 기기의 serial 목록.
    func onlineDevices() throws -> [String] {
        try run(["devices"])
            .split(separator: "\n")
            .dropFirst()
            .compactMap { line in
                let cols = line.split(whereSeparator: { $0 == "\t" || $0 == " " })
                return cols.count >= 2 && cols[1] == "device" ? String(cols[0]) : nil
            }
    }
}
