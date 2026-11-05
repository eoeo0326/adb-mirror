package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.adb.PortForward
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
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

/** inspector 준비·연결 실패. [failure]로 UI 문구를 고른다. */
class InspectorException(val failure: InspectorFailure, message: String, cause: Throwable? = null) : Exception(message, cause)
