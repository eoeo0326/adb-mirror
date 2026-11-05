package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.adb.PortForward
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** transport 데몬에 이어진 연결 하나. [close]가 채널·forward·(우리가 띄운) 데몬을 정리한다. */
class TransportConnection internal constructor(
    val serial: String,
    /** `Command.stream_id`에 쓰는 기기 id. serial·boot id로 만든다. */
    val deviceId: Long,
    val apiLevel: Int,
    /** 기기가 지원하는 ABI(기기 순서). */
    val abis: List<String>,
    val client: TransportClient,
    /** 이미 돌던 데몬(예: Android Studio가 띄운 것)을 함께 쓰는지. 그렇다면 닫을 때 끄지 않는다. */
    val reusedDaemon: Boolean,
    private val cleanup: suspend () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    /** 여러 번 불러도 한 번만 정리한다. 취소 중에도 끝까지 돈다. */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable) { cleanup() }
    }
}

/**
 * Android Studio 파일을 기기에 올리고 transport 데몬을 띄워 gRPC로 붙는다.
 * 순서: API 확인 → ABI → push(같은 sha면 건너뜀) → 데몬 재사용·기동 → forward → 채널 → 연결 확인.
 * 중간에 실패하거나 취소되면 그때까지 연 것을 되돌린다.
 */
class TransportLauncher(
    private val shell: DeviceShell,
    private val channelFactory: (port: Int) -> ManagedChannel = ::defaultChannel,
    private val daemonStartTimeoutMs: Long = 10_000,
    private val log: (String) -> Unit = {},
) {
    suspend fun launch(serial: String, install: StudioInstall, onStep: (InspectorStep) -> Unit = {}): TransportConnection {
        val api = shell.shell(serial, "getprop ro.build.version.sdk").trim().toIntOrNull()
            ?: throw InspectorException(InspectorFailure.InspectorFailed, "기기 API 수준을 읽지 못했습니다")
        if (api < MIN_API) throw InspectorException(InspectorFailure.ApiTooLow, "API $api")
        val abis = DeviceFiles.supportedAbis(shell.shell(serial, "getprop ro.product.cpu.abilist"))
        if (abis.isEmpty()) throw InspectorException(InspectorFailure.InspectorFailed, "지원하는 ABI가 없습니다")

        onStep(InspectorStep.PushingFiles)
        pushFiles(serial, install, abis)

        // 아래에서 연 자원은 실패·취소 시 거꾸로 되돌린다.
        val undo = ArrayDeque<suspend () -> Unit>()
        suspend fun rollback() = withContext(NonCancellable) { while (undo.isNotEmpty()) runCatching { undo.removeLast()() } }
        try {
            onStep(InspectorStep.StartingDaemon)
            val reused = shell.shell(serial, "pidof transport || true").isNotBlank()
            if (reused) {
                log("이미 도는 transport 데몬을 함께 씁니다")
            } else {
                val daemon = startDaemon(serial)
                undo += {
                    daemon.stop()
                    runCatching { shell.shell(serial, "pkill -f ${DeviceFiles.DAEMON} || true") }
                }
            }

            val forward: PortForward = shell.forward(serial, DeviceFiles.SOCKET_NAME)
            undo += { forward.close() }
            val channel = channelFactory(forward.port)
            undo += { channel.shutdownNow(); withContext(Dispatchers.IO) { channel.awaitTermination(2, TimeUnit.SECONDS) } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            // GetEvents 스트림은 클라이언트를 만들 때 바로 연다. 명령보다 먼저여야 응답을 놓치지 않는다.
            val client = TransportClient(channel, scope)
            undo += { client.close(); scope.cancel() }

            try {
                client.ping()
            } catch (e: Exception) {
                throw InspectorException(InspectorFailure.Disconnected, "transport 데몬에 연결하지 못했습니다: ${e.message}", e)
            }
            val bootId = shell.shell(serial, "cat /proc/sys/kernel/random/boot_id").trim()

            val steps = undo.toList()
            undo.clear()
            return TransportConnection(serial, deviceId(serial, bootId), api, abis, client, reused) {
                // 정리 순서: 이벤트 스트림 → 채널 → forward → 데몬(우리가 띄운 것만).
                steps.asReversed().forEach { runCatching { it() } }
            }
        } catch (e: Throwable) {
            rollback()
            throw e
        }
    }

    private suspend fun startDaemon(serial: String): RemoteProcess {
        val ready = CompletableDeferred<Unit>()
        val output = StringBuilder()
        val process = shell.startProcess(serial, listOf(DeviceFiles.DAEMON, "-config_file=${DeviceFiles.DAEMON_CONFIG}")) { line ->
            synchronized(output) { output.appendLine(line) }
            if ("Server listening on" in line) ready.complete(Unit)
        }
        // 데몬이 곧바로 끝나면 기다리지 않는다.
        val watcher = CoroutineScope(Dispatchers.IO).launch {
            val code = process.awaitExit()
            ready.completeExceptionally(InspectorException(InspectorFailure.InspectorFailed, "transport 데몬이 종료됐습니다 (exit $code)"))
        }
        try {
            withTimeoutOrNull(daemonStartTimeoutMs) { ready.await() }
                ?: throw InspectorException(InspectorFailure.InspectorFailed, "transport 데몬이 ${daemonStartTimeoutMs / 1000}초 안에 뜨지 않았습니다")
        } catch (e: Throwable) {
            log("데몬 출력:\n" + synchronized(output) { output.toString() })
            withContext(NonCancellable) { process.stop() }
            throw e
        } finally {
            watcher.cancel()
        }
        return process
    }

    /** 기기 파일과 sha256이 같으면 건너뛴다(Android Studio와 같은 방식). */
    private suspend fun pushFiles(serial: String, install: StudioInstall, abis: List<String>) {
        val files = buildList<Pair<Upload, String>> {
            add(Upload.Local(install.daemon(abis.first())) to DeviceFiles.DAEMON)
            add(Upload.Local(install.perfaJar) to DeviceFiles.PERFA_JAR)
            abis.forEach { abi -> add(Upload.Local(install.agent(abi)) to DeviceFiles.agentPath(DeviceFiles.cpuArch(abi)!!)) }
            add(Upload.Local(install.viewInspectorJar) to DeviceFiles.VIEW_INSPECTOR_JAR)
            add(Upload.Bytes(DeviceFiles.daemonConfig()) to DeviceFiles.DAEMON_CONFIG)
            add(Upload.Bytes(DeviceFiles.agentConfig()) to DeviceFiles.AGENT_CONFIG)
        }
        shell.shell(serial, "mkdir -p ${DeviceFiles.DIR}")
        val remote = parseSha256(shell.shell(serial, "sha256sum ${files.joinToString(" ") { it.second }} 2>/dev/null || true"))
        files.forEach { (upload, path) ->
            if (remote[path] == upload.sha256()) return@forEach
            when {
                upload is Upload.Local && path.endsWith(".jar") -> shell.pushDex(serial, upload.file, path)
                upload is Upload.Local -> shell.push(serial, upload.file, path)
                upload is Upload.Bytes -> shell.push(serial, upload.data, path)
            }
            log("push $path")
        }
        // 예전에 쓰기 가능으로 올린 jar가 남아 있어도 dex로 로드되도록 읽기 전용으로 맞춘다.
        val jars = files.map { it.second }.filter { it.endsWith(".jar") }
        shell.shell(serial, "chmod 755 ${DeviceFiles.DAEMON}; chmod 444 ${jars.joinToString(" ")}")
    }

    private sealed interface Upload {
        fun sha256(): String

        class Local(val file: File) : Upload {
            override fun sha256() = sha256Hex(file.readBytes())
        }

        class Bytes(val data: ByteArray) : Upload {
            override fun sha256() = sha256Hex(data)
        }
    }

    companion object {
        const val MIN_API = 29

        /** transport 메시지(스크린샷이 든 이벤트)는 크다. Android Studio도 512MB로 둔다. */
        private const val MAX_MESSAGE_BYTES = 512 * 1024 * 1024

        fun defaultChannel(port: Int): ManagedChannel =
            ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().maxInboundMessageSize(MAX_MESSAGE_BYTES).build()

        /** 기기 id. Android Studio처럼 serial과 boot id의 SHA-256으로 만든다(앞 8바이트). 데몬은 이 값을 명령의 stream id로만 쓴다. */
        internal fun deviceId(serial: String, bootId: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest((serial + bootId).toByteArray())
            return digest.take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        }

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** `sha256sum` 출력(`<hex>  <path>`)을 경로→sha 맵으로. */
        internal fun parseSha256(output: String): Map<String, String> = output.lineSequence()
            .mapNotNull { line -> Regex("^([0-9a-f]{64})\\s+\\*?(\\S.*)$").find(line.trim())?.destructured }
            .associate { (sha, path) -> path to sha }
    }
}
