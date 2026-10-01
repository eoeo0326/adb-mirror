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

    /**
     * 연결을 목록에 넣는다. 같은 기기의 이전 연결은 닫는다(두 읽기 루프가 같은 엔드포인트를 나눠 읽지 않게).
     * 연결이 끊기면(USB를 뽑음) 목록에서 뺀다.
     */
    suspend fun add(serial: String, connection: AdbConnection) {
        var previous: AdbConnection? = null
        connections.update { previous = it[serial]; it + (serial to connection) }
        previous?.takeIf { it !== connection }?.close()
        scope.launch {
            connection.awaitClosed()
            connections.update { if (it[serial] === connection) it - serial else it }
        }
    }

    /** 이 기기의 연결을 닫고 목록에서 뺀다. 없으면 아무것도 하지 않는다. */
    suspend fun remove(serial: String) {
        var removed: AdbConnection? = null
        connections.update { removed = it[serial]; it - serial }
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
