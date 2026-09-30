package io.github.eoeo0326.adbmirror.core.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** adb 실행 파일을 `ProcessBuilder`로 호출하는 Desktop 전송. */
class AdbBinaryTransport(private val adb: File) : AdbTransport {

    override suspend fun devices(): List<AdbDevice> = AdbOutputParser.parseDevices(text(run(listOf("devices", "-l"))))

    override fun trackDevices(): Flow<List<AdbDevice>> = callbackFlow {
        val process = ProcessBuilder(adb.path, "track-devices", "-l").redirectErrorStream(true).start()
        val reader = thread(name = "adb-track-devices", isDaemon = true) {
            val buffer = StringBuilder()
            val chunk = CharArray(4096)
            process.inputStream.reader().use { input ->
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.append(chunk, 0, n)
                    AdbOutputParser.takeTrackMessages(buffer).forEach { trySend(AdbOutputParser.parseDevices(it)) }
                }
            }
            close(AdbException("adb track-devices가 끝났습니다 (exit ${process.waitFor()})"))
        }
        awaitClose {
            process.destroy()
            reader.interrupt()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun push(serial: String, data: ByteArray, remotePath: String) {
        val temp = withContext(Dispatchers.IO) { File.createTempFile("adb-mirror-push", null).apply { writeBytes(data) } }
        try {
            run(listOf("-s", serial, "push", temp.path, remotePath))
        } finally {
            temp.delete()
        }
    }

    override suspend fun shell(serial: String, command: List<String>): String =
        text(run(listOf("-s", serial, "shell") + command))

    override suspend fun execOut(serial: String, command: List<String>): ByteArray =
        run(listOf("-s", serial, "exec-out") + command)

    override suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit): RemoteProcess {
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(listOf(adb.path, "-s", serial, "shell") + command).redirectErrorStream(true).start()
        }
        thread(name = "adb-process-output", isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine(onOutput)
        }
        return object : RemoteProcess {
            override suspend fun awaitExit(): Int = runInterruptible(Dispatchers.IO) { process.waitFor() }
            override suspend fun stop() = withContext(Dispatchers.IO) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                Unit
            }
        }
    }

    override suspend fun openLocalAbstract(serial: String, name: String): DeviceStream {
        // tcp:0을 주면 adb가 빈 로컬 포트를 골라 출력한다.
        val port = text(run(listOf("-s", serial, "forward", "tcp:0", "localabstract:$name"))).trim().toIntOrNull()
            ?: throw AdbException("adb forward 포트를 해석하지 못했습니다")
        val removeForward: suspend () -> Unit = {
            runCatching { run(listOf("-s", serial, "forward", "--remove", "tcp:$port")) }
        }
        val socket = try {
            withContext(Dispatchers.IO) {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress("127.0.0.1", port), 2_000)
                }
            }
        } catch (e: Exception) {
            removeForward()
            throw e
        }
        return DeviceStream(
            source = SocketSource(socket.getInputStream()),
            sink = SocketSink(socket.getOutputStream()),
            onClose = {
                withContext(Dispatchers.IO) { runCatching { socket.close() } }
                removeForward()
            },
        )
    }

    /** adb 명령을 끝까지 실행한다. 실패하면 stderr(없으면 stdout)를 담아 [AdbException]. */
    private suspend fun run(args: List<String>): ByteArray = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(adb.path) + args).start()
        var stderr = ByteArray(0)
        val errReader = thread(isDaemon = true) { stderr = process.errorStream.readBytes() }
        val stdout = process.inputStream.readBytes()
        val code = process.waitFor()
        errReader.join()
        if (code != 0) {
            val message = text(stderr.takeIf { it.isNotEmpty() } ?: stdout).trim()
            throw AdbException("adb ${args.joinToString(" ")} 실패 (exit $code): $message")
        }
        stdout
    }

    private fun text(bytes: ByteArray) = bytes.decodeToString()

    private class SocketSource(private val input: InputStream) : ByteSource {
        override suspend fun readFully(count: Int): ByteArray = runInterruptible(Dispatchers.IO) {
            val out = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val n = try { input.read(out, offset, count - offset) } catch (e: java.io.IOException) { -1 }
                if (n < 0) throw EndOfStreamException()
                offset += n
            }
            out
        }

        override suspend fun close() = withContext(Dispatchers.IO) { runCatching { input.close() }; Unit }
    }

    private class SocketSink(private val output: OutputStream) : ByteSink {
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            try {
                output.write(bytes)
                output.flush()
            } catch (e: java.io.IOException) {
                throw EndOfStreamException("쓰기 실패: ${e.message}")
            }
        }

        override suspend fun close() = withContext(Dispatchers.IO) { runCatching { output.close() }; Unit }
    }

    companion object {
        /** [AdbLocator]로 adb를 찾는다. 없으면 null. */
        fun locate(configuredPath: String? = null): AdbBinaryTransport? = AdbLocator.locate(configuredPath)?.let(::AdbBinaryTransport)
    }
}
