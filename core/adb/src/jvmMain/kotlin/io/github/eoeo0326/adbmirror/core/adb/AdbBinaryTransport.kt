package io.github.eoeo0326.adbmirror.core.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
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

/** adb 실행 파일을 `ProcessBuilder`로 호출하는 Desktop 전송. 무선 디버깅은 `adb pair`·`adb connect`로 붙는다. */
class AdbBinaryTransport(private val adb: File) : WirelessAdbTransport {

    override suspend fun devices(): List<AdbDevice> = AdbOutputParser.parseDevices(text(run(listOf("devices", "-l"))))

    /**
     * 전체 목록 스냅샷이라 소비가 늦으면 최신 목록만 남긴다(conflate).
     * adb 서버가 꺼져 있으면 `start-server`로 먼저 띄운다. track 클라이언트가 서버를 띄우는 도중에 끊기면
     * 서버도 뜨다 만다. stderr(`* daemon …` 안내)는 track 메시지와 섞이지 않게 따로 읽어 로그로만 남긴다.
     */
    override fun trackDevices(): Flow<List<AdbDevice>> = callbackFlow {
        run(listOf("start-server"))
        val process = ProcessBuilder(adb.path, "track-devices", "-l").start()
        thread(name = "adb-track-devices-stderr", isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine { println("adb track-devices: $it") }
        }
        thread(name = "adb-track-devices", isDaemon = true) {
            // 리더에서 난 예외도 Flow로 넘겨야 수집 쪽(retryWhen)이 다시 붙는다. 그냥 두면 Flow가 영원히 멈춘다.
            val cause = try {
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
                AdbException("adb track-devices가 끝났습니다 (exit ${process.waitFor()})")
            } catch (e: Throwable) {
                e
            } finally {
                process.destroy()
            }
            close(cause)
        }
        awaitClose { process.destroy() }
    }.conflate().flowOn(Dispatchers.IO)

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
            override suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
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
        // 취소 중에도 forward가 남지 않도록 정리는 NonCancellable로 돈다.
        val removeForward: suspend () -> Unit = {
            withContext(NonCancellable) { runCatching { run(listOf("-s", serial, "forward", "--remove", "tcp:$port")) } }
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
                withContext(NonCancellable + Dispatchers.IO) { runCatching { socket.close() } }
                removeForward()
            },
        )
    }

    override suspend fun pair(host: String, port: Int, code: String) {
        val result = exec(listOf("pair", address(host, port), code))
        AdbOutputParser.pairFailure(result.text)?.let { throw AdbException(it) }
    }

    override suspend fun connect(host: String, port: Int): AdbDevice {
        val serial = address(host, port)
        val result = exec(listOf("connect", serial))
        AdbOutputParser.connectFailure(result.text)?.let { throw AdbException(it) }
        // adb 서버가 기기 정보(model)를 채우기까지 잠깐 걸린다.
        repeat(10) {
            devices().firstOrNull { it.serial == serial && it.model != null }?.let { return it }
            delay(200)
        }
        return devices().firstOrNull { it.serial == serial } ?: AdbDevice(serial, "device", null)
    }

    override suspend fun disconnect(serial: String) {
        run(listOf("disconnect", serial))
    }

    /** IPv6 주소는 `[::1]:5555`처럼 대괄호로 감싼다. */
    private fun address(host: String, port: Int) = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    /** adb 명령을 끝까지 실행한다. 실패하면 stderr(없으면 stdout)를 담아 [AdbException]. */
    private suspend fun run(args: List<String>): ByteArray {
        val result = exec(args)
        if (result.code != 0) {
            val message = text(result.stderr.takeIf { it.isNotEmpty() } ?: result.stdout).trim()
            throw AdbException("adb ${args.joinToString(" ")} 실패 (exit ${result.code}): $message")
        }
        return result.stdout
    }

    private class ExecResult(val code: Int, val stdout: ByteArray, val stderr: ByteArray) {
        val text: String get() = (stdout.decodeToString() + "\n" + stderr.decodeToString()).trim()
    }

    /** 취소되면 프로세스를 강제 종료한다. 그래야 파이프를 읽던 쪽도 EOF로 풀려 코루틴이 끝난다. */
    private suspend fun exec(args: List<String>): ExecResult = coroutineScope {
        val process = withContext(Dispatchers.IO) { ProcessBuilder(listOf(adb.path) + args).start() }
        val stdout = async(Dispatchers.IO) { process.inputStream.readBytes() }
        val stderr = async(Dispatchers.IO) { process.errorStream.readBytes() }
        val code = try {
            runInterruptible(Dispatchers.IO) { process.waitFor() }
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        }
        ExecResult(code, stdout.await(), stderr.await())
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
