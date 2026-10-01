package io.github.eoeo0326.adbmirror.core.adb.protocol

import io.github.eoeo0326.adbmirror.core.adb.AdbDevice
import io.github.eoeo0326.adbmirror.core.adb.AdbException
import io.github.eoeo0326.adbmirror.core.adb.AdbTransport
import io.github.eoeo0326.adbmirror.core.adb.DeviceStream
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * adb 서버 없이 [AdbConnection]으로 직접 붙은 기기들의 [AdbTransport](웹 WebUSB). 기기 목록은 [add]한 연결이다.
 * 셸 명령은 adb처럼 공백으로 이어 `shell:`에 넘긴다(따옴표가 필요한 인자는 부르는 쪽이 감싼다).
 */
class DirectAdbTransport(private val scope: CoroutineScope) : AdbTransport {
    private val connections = MutableStateFlow<Map<String, AdbConnection>>(emptyMap())

    fun add(serial: String, connection: AdbConnection) = connections.update { it + (serial to connection) }

    suspend fun remove(serial: String) {
        val removed = connections.value[serial]
        connections.update { it - serial }
        removed?.close()
    }

    private fun connection(serial: String): AdbConnection =
        connections.value[serial] ?: throw AdbException("연결되지 않은 기기입니다: $serial")

    private fun toDevices(map: Map<String, AdbConnection>) = map.map { (serial, c) -> AdbDevice(serial, "device", c.model) }

    override suspend fun devices(): List<AdbDevice> = toDevices(connections.value)

    override fun trackDevices(): Flow<List<AdbDevice>> = connections.map(::toDevices)

    override suspend fun push(serial: String, data: ByteArray, remotePath: String) = connection(serial).push(data, remotePath)

    override suspend fun shell(serial: String, command: List<String>): String = connection(serial).shell(command.joinToString(" "))

    override suspend fun execOut(serial: String, command: List<String>): ByteArray =
        connection(serial).execBytes("exec:" + command.joinToString(" "))

    /** `shell:`로 시작해 출력을 줄 단위로 넘긴다. 프로토콜 v1에는 종료 코드가 없어 스트림이 끝나면 0을 돌려준다. */
    override suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit): RemoteProcess {
        val stream = connection(serial).open("shell:" + command.joinToString(" "))
        val exited = CompletableDeferred<Int>()
        scope.launch {
            val line = StringBuilder()
            try {
                while (true) {
                    val chunk = stream.readChunk() ?: break
                    for (ch in chunk.decodeToString()) {
                        if (ch == '\n') {
                            onOutput(line.toString().trimEnd('\r'))
                            line.clear()
                        } else {
                            line.append(ch)
                        }
                    }
                }
                if (line.isNotEmpty()) onOutput(line.toString())
            } finally {
                exited.complete(0)
            }
        }
        return object : RemoteProcess {
            override suspend fun awaitExit(): Int = exited.await()
            override suspend fun stop() = withContext(NonCancellable) { stream.close() }
        }
    }

    override suspend fun openLocalAbstract(serial: String, name: String): DeviceStream {
        val stream = connection(serial).open("localabstract:$name")
        return DeviceStream(stream, stream) { stream.close() }
    }
}
