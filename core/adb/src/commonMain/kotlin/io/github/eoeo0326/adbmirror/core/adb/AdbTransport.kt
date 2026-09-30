package io.github.eoeo0326.adbmirror.core.adb

import kotlinx.coroutines.flow.Flow

/** adb가 보고한 기기 한 줄. [state]는 adb 원문(`device`, `unauthorized`, `offline` …). */
data class AdbDevice(val serial: String, val state: String, val model: String? = null)

/** 기기의 localabstract 소켓 하나에 이어진 양방향 스트림. */
class DeviceStream(
    val source: ByteSource,
    val sink: ByteSink,
    private val onClose: suspend () -> Unit,
) {
    suspend fun close() = onClose()
}

/** 기기에서 계속 돌아가는 명령(scrcpy 서버 등). */
interface RemoteProcess {
    /** 명령이 끝날 때까지 기다리고 종료 코드를 돌려준다. */
    suspend fun awaitExit(): Int

    /** 로컬 쪽 연결을 끊어 명령을 끝낸다. */
    suspend fun stop()
}

/**
 * 플랫폼별 adb 전송. Desktop은 adb 바이너리, Android는 Kadb, Web은 WebUSB 위의 ADB 프로토콜로 구현한다.
 * 모든 호출은 대상 기기 serial을 받는다.
 */
interface AdbTransport {
    suspend fun devices(): List<AdbDevice>

    /** 연결·해제될 때마다 전체 목록을 내보낸다. */
    fun trackDevices(): Flow<List<AdbDevice>>

    suspend fun push(serial: String, data: ByteArray, remotePath: String)

    /** 짧은 명령을 실행하고 표준 출력을 돌려준다. 실패하면 [AdbException]. */
    suspend fun shell(serial: String, command: List<String>): String

    /** 바이너리 출력을 그대로 돌려준다(`exec-out`, 예: screencap -p). */
    suspend fun execOut(serial: String, command: List<String>): ByteArray

    /** 오래 도는 명령을 시작한다. 출력은 [onOutput]으로 한 줄씩 전달한다. */
    suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit = {}): RemoteProcess

    /** 기기의 `localabstract:<name>` 소켓에 연결한다. */
    suspend fun openLocalAbstract(serial: String, name: String): DeviceStream
}

class AdbException(message: String) : Exception(message)
