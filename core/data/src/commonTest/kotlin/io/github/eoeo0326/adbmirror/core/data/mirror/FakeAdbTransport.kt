package io.github.eoeo0326.adbmirror.core.data.mirror

import io.github.eoeo0326.adbmirror.core.adb.AdbDevice
import io.github.eoeo0326.adbmirror.core.adb.AdbTransport
import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.adb.ByteSink
import io.github.eoeo0326.adbmirror.core.adb.ByteSource
import io.github.eoeo0326.adbmirror.core.adb.DeviceStream
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class RecordingSink : ByteSink {
    val writes = mutableListOf<ByteArray>()
    var closed = false
    override suspend fun write(bytes: ByteArray) { writes += bytes }
    override suspend fun close() { closed = true }
}

class FakeStream(source: ByteSource, val sink: RecordingSink = RecordingSink()) {
    var closed = false
    val stream = DeviceStream(source, sink) { closed = true }

    companion object {
        fun of(bytes: ByteArray) = FakeStream(ByteArraySource(bytes))
    }
}

class FakeProcess : RemoteProcess {
    val exit = CompletableDeferred<Int>()
    var stopped = false
    override suspend fun awaitExit(): Int = exit.await()
    override suspend fun stop() { stopped = true; exit.complete(0) }
}

class FakeAdbTransport(private val streams: ArrayDeque<FakeStream> = ArrayDeque()) : AdbTransport {
    val pushed = mutableListOf<Pair<String, String>>()
    val commands = mutableListOf<List<String>>()
    val shellCommands = mutableListOf<List<String>>()
    val opened = mutableListOf<String>()
    val process = FakeProcess()
    val deviceUpdates = MutableSharedFlow<List<AdbDevice>>(replay = 1)
    var shellReply: (List<String>) -> String = { "" }
    /** n번째(0부터) openLocalAbstract 호출을 실패시킨다. */
    var failOpenAt: Int? = null

    override suspend fun devices() = deviceUpdates.replayCache.lastOrNull() ?: emptyList()
    override fun trackDevices(): Flow<List<AdbDevice>> = deviceUpdates
    override suspend fun push(serial: String, data: ByteArray, remotePath: String) { pushed += serial to remotePath }
    override suspend fun shell(serial: String, command: List<String>): String { shellCommands += command; return shellReply(command) }
    override suspend fun execOut(serial: String, command: List<String>) = ByteArray(0)
    override suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit): RemoteProcess {
        commands += command
        return process
    }
    override suspend fun openLocalAbstract(serial: String, name: String): DeviceStream {
        if (failOpenAt == opened.size) { opened += name; error("forward 실패") }
        opened += name
        return streams.removeFirst().stream
    }
}
