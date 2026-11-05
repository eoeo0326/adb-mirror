package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.adb.PortForward
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** inspector가 기기에 하는 adb 작업. 실제로는 [AdbDeviceShell]이고, 테스트는 가짜를 끼운다. */
interface DeviceShell {
    /** 짧은 셸 명령의 표준 출력. 종료 코드가 0이 아니면 예외. */
    suspend fun shell(serial: String, command: String): String

    suspend fun push(serial: String, local: File, remotePath: String)

    suspend fun push(serial: String, data: ByteArray, remotePath: String)

    suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit): RemoteProcess

    suspend fun forward(serial: String, localAbstract: String): PortForward
}

class AdbDeviceShell(private val adb: AdbBinaryTransport) : DeviceShell {
    override suspend fun shell(serial: String, command: String): String = adb.shell(serial, listOf(command))

    override suspend fun push(serial: String, local: File, remotePath: String) =
        adb.push(serial, withContext(Dispatchers.IO) { local.readBytes() }, remotePath)

    override suspend fun push(serial: String, data: ByteArray, remotePath: String) = adb.push(serial, data, remotePath)

    override suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit): RemoteProcess =
        adb.startProcess(serial, command, onOutput)

    override suspend fun forward(serial: String, localAbstract: String): PortForward = adb.forward(serial, localAbstract)
}

/**
 * dex(jar)를 기기에 올리고 읽기 전용(444)으로 둔다. Android 14부터 쓰기 가능한 dex는 로드하지 않는다
 * (`Writable dex file … is not allowed`). 읽기 전용 파일 위로 push하지 않도록 먼저 지운다.
 */
suspend fun DeviceShell.pushDex(serial: String, local: File, remotePath: String) {
    shell(serial, "rm -f $remotePath")
    push(serial, local, remotePath)
    shell(serial, "chmod 444 $remotePath")
}
