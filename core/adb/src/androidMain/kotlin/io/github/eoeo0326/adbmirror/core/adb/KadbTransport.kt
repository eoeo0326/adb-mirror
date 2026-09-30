package io.github.eoeo0326.adbmirror.core.adb

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.AdbShellPacketV2
import com.flyfishxu.kadb.shell.AdbShellStream
import com.flyfishxu.kadb.stream.AdbStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.EOFException
import kotlin.concurrent.thread

/**
 * Kadb(순수 Kotlin ADB 클라이언트)로 무선 디버깅 기기에 붙는 전송. Android 앱에서 쓴다.
 * 기기 serial은 `host:port`이고, 기기마다 ADB 연결 하나를 두고 그 위에 스트림을 연다.
 * Kadb는 블로킹 API라 IO 디스패처에서 부르고, 오래 걸리는 읽기는 취소되면 스트림을 닫아 깨운다.
 */
class KadbTransport(keyStore: KadbKeyStore) : WirelessAdbTransport {
    private val connections = ConnectionRegistry<Kadb> { kadb -> withContext(Dispatchers.IO) { kadb.close() } }

    init {
        keyStore.install()
    }

    override suspend fun pair(host: String, port: Int, code: String) {
        try {
            Kadb.pair(host, port, code)
        } catch (e: Exception) {
            throw AdbException("페어링하지 못했습니다. 코드와 페어링 포트를 확인하세요 (${e.message ?: e::class.simpleName})")
        }
    }

    override suspend fun connect(host: String, port: Int): AdbDevice {
        val serial = "$host:$port"
        // 같은 기기를 동시에 연결해도 Kadb 연결은 하나만 열고, 연 연결은 반드시 등록하거나 닫는다.
        return connections.getOrOpen(serial) {
            // Kadb의 socketTimeout은 연결 내내 걸려서, 걸어 두면 화면이 멈춘 동안(영상 없음) 연결이 끊긴다.
            // 그래서 먼저 제한 시간을 둔 확인용 연결로 인증·첫 명령까지 해 보고, 되면 제한 없는 연결을 연다.
            // (핸드셰이크 중 소켓은 Kadb 안에만 있어 밖에서 닫아 깨울 수 없다.)
            val model = withContext(Dispatchers.IO) {
                val probe = Kadb.create(host, port, connectTimeout = CONNECT_TIMEOUT_MS, socketTimeout = PROBE_READ_TIMEOUT_MS)
                try {
                    probe.shell("getprop ro.product.model").output.trim()
                } catch (e: Exception) {
                    val reason = if (e is java.net.SocketTimeoutException) "${PROBE_READ_TIMEOUT_MS / 1000}초 동안 응답이 없습니다" else e.message ?: e::class.simpleName
                    throw AdbException("연결하지 못했습니다. 무선 디버깅이 켜져 있고 이 앱과 페어링했는지 확인하세요 ($reason)")
                } finally {
                    runCatching { probe.close() }
                }
            }
            val kadb = Kadb.create(host, port, connectTimeout = CONNECT_TIMEOUT_MS, socketTimeout = 0)
            kadb to AdbDevice(serial, "device", model.ifBlank { null })
        }
    }

    override suspend fun disconnect(serial: String) = connections.remove(serial)

    override suspend fun devices(): List<AdbDevice> = connections.entries.value.values.map { it.device }

    override fun trackDevices(): Flow<List<AdbDevice>> = connections.entries.map { m -> m.values.map { it.device } }

    private fun kadb(serial: String): Kadb = connections[serial] ?: throw AdbException("연결되지 않은 기기입니다: $serial")

    override suspend fun push(serial: String, data: ByteArray, remotePath: String) = withContext(Dispatchers.IO) {
        kadb(serial).push(Buffer().write(data), remotePath, FILE_MODE, System.currentTimeMillis())
    }

    override suspend fun shell(serial: String, command: List<String>): String = withContext(Dispatchers.IO) {
        val response = kadb(serial).shell(command.joinToString(" "))
        if (response.exitCode != 0) {
            throw AdbException("adb shell ${command.joinToString(" ")} 실패 (exit ${response.exitCode}): ${response.errorOutput.ifBlank { response.output }.trim()}")
        }
        response.output
    }

    override suspend fun execOut(serial: String, command: List<String>): ByteArray = withContext(Dispatchers.IO) {
        kadb(serial).open("exec:${command.joinToString(" ")}").use { it.source.readByteArray() }
    }

    override suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit): RemoteProcess {
        val shell = withContext(Dispatchers.IO) { kadb(serial).openShell(command.joinToString(" ")) }
        val exit = CompletableDeferred<Int>()
        thread(name = "kadb-process-output", isDaemon = true) {
            val lines = StringBuilder()
            try {
                while (true) {
                    val packet = shell.read()
                    when (packet.id) {
                        AdbShellPacketV2.ID_STDOUT, AdbShellPacketV2.ID_STDERR -> {
                            lines.append(String(packet.payload, Charsets.UTF_8))
                            var nl = lines.indexOf("\n")
                            while (nl >= 0) {
                                onOutput(lines.substring(0, nl).trimEnd('\r'))
                                lines.delete(0, nl + 1)
                                nl = lines.indexOf("\n")
                            }
                        }
                        AdbShellPacketV2.ID_EXIT -> {
                            exit.complete(packet.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0)
                            return@thread
                        }
                    }
                }
            } catch (_: Exception) {
                // 스트림이 닫혔다(stop 또는 연결 끊김). 종료 코드를 알 수 없으면 -1.
            } finally {
                exit.complete(-1)
            }
        }
        return object : RemoteProcess {
            override suspend fun awaitExit(): Int = exit.await()
            override suspend fun stop() = withContext(Dispatchers.IO + NonCancellable) { closeQuietly(shell) }
        }
    }

    override suspend fun openLocalAbstract(serial: String, name: String): DeviceStream {
        val kadb = kadb(serial)
        // Kadb는 adbd의 OKAY를 기다리므로, 서버가 소켓을 열기 전이면 CLSE를 받아 IOException으로 끝난다.
        val stream = try {
            withContext(Dispatchers.IO) { kadb.open("localabstract:$name") }
        } catch (_: java.io.IOException) {
            throw SocketNotReadyException(name)
        }
        return DeviceStream(StreamSource(stream.source, stream), StreamSink(stream.sink, stream)) {
            withContext(Dispatchers.IO + NonCancellable) { runCatching { stream.close() } }
        }
    }

    private fun closeQuietly(shell: AdbShellStream) = runCatching { shell.close() }.let { }

    /** 블로킹 읽기. 취소되면 스트림을 닫아 읽기를 깨운다. */
    private class StreamSource(private val source: BufferedSource, private val stream: AdbStream) : ByteSource {
        override suspend fun readFully(count: Int): ByteArray = try {
            runInterruptible(Dispatchers.IO) { source.readByteArray(count.toLong()) }
        } catch (_: EOFException) {
            throw EndOfStreamException()
        } catch (e: java.io.IOException) {
            throw EndOfStreamException(e.message ?: "스트림이 닫혔습니다")
        }

        override suspend fun close() = withContext(Dispatchers.IO + NonCancellable) { runCatching { stream.close() }.let { } }
    }

    private class StreamSink(private val sink: BufferedSink, private val stream: AdbStream) : ByteSink {
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            sink.write(bytes)
            sink.flush()
            Unit
        }

        override suspend fun close() = withContext(Dispatchers.IO + NonCancellable) { runCatching { stream.close() }.let { } }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        /** 확인용 연결의 읽기 제한 시간. Kadb는 실패하면 5번까지 다시 시도하므로 최악 약 25초. */
        const val PROBE_READ_TIMEOUT_MS = 5_000
        const val FILE_MODE = 420 // 0644
    }
}
