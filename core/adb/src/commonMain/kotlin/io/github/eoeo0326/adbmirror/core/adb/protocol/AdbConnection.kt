package io.github.eoeo0326.adbmirror.core.adb.protocol

import io.github.eoeo0326.adbmirror.core.adb.AdbException
import io.github.eoeo0326.adbmirror.core.adb.ByteSink
import io.github.eoeo0326.adbmirror.core.adb.ByteSource
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.adb.SocketNotReadyException
import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** ADB 메시지가 오가는 바이트 통로. JVM TCP 소켓(`adb tcpip`), 웹 WebUSB bulk 엔드포인트가 구현한다. */
interface AdbChannel : ByteSource, ByteSink

/**
 * adb 서버 없이 기기의 adbd와 직접 말하는 연결 하나. [connect]로 CNXN·AUTH를 마치면 [scope]에서 메시지를 읽어
 * 스트림(OPEN·WRTE·OKAY·CLSE)으로 나눠 준다. 스트림마다 쓰기는 한 번에 하나(상대 OKAY를 받은 뒤 다음 WRTE)다.
 */
class AdbConnection private constructor(
    private val channel: AdbChannel,
    /** 기기가 CNXN에 실어 보낸 문자열(`device::ro.product.name=…;features=…`). */
    val banner: String,
    private val maxPayload: Int,
    scope: CoroutineScope,
) {
    private val writeLock = Mutex()
    private val lock = Mutex()
    private val streams = mutableMapOf<Int, AdbStream>()
    private val opening = mutableMapOf<Int, CompletableDeferred<AdbStream>>()
    private var nextId = 1
    private var failure: Throwable? = null
    private val ended = CompletableDeferred<Unit>()

    init {
        scope.launch { readLoop() }
    }

    /** 연결이 끊기거나 [close]할 때까지 기다린다(USB를 뽑으면 읽기 루프가 끝남). */
    suspend fun awaitClosed() = ended.await()

    /** banner의 `ro.product.model` 값. */
    val model: String? get() = bannerProperty("ro.product.model")

    fun bannerProperty(name: String): String? =
        banner.substringAfter("::", "").split(';').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    /**
     * 서비스(`shell:…`, `exec:…`, `sync:`, `localabstract:…`)에 스트림을 연다.
     * 기기가 거절하면 `localabstract:`는 [SocketNotReadyException](서버가 아직 소켓을 열지 않음), 그 밖은 [AdbException].
     */
    suspend fun open(service: String): AdbStream {
        val waiting = CompletableDeferred<AdbStream>()
        val id = lock.withLock {
            failure?.let { throw AdbException("연결이 끊겼습니다: ${it.message}") }
            nextId++.also { opening[it] = waiting }
        }
        send(AdbMessage(AdbMessage.OPEN, id, 0, (service + "\u0000").encodeToByteArray()))
        return try {
            waiting.await()
        } catch (e: OpenRefused) {
            if (service.startsWith("localabstract:")) throw SocketNotReadyException(service.removePrefix("localabstract:"))
            throw AdbException("기기가 서비스를 열지 않았습니다: $service")
        } finally {
            lock.withLock { opening.remove(id) }
        }
    }

    /** 명령을 실행하고 출력 전체를 돌려준다(`shell:`). */
    suspend fun shell(command: String): String = execBytes("shell:$command").decodeToString()

    /** 바이너리 출력을 그대로 돌려준다(`exec:`, 줄바꿈 변환 없음). */
    suspend fun execBytes(service: String): ByteArray {
        val stream = open(service)
        val out = mutableListOf<ByteArray>()
        while (true) out += stream.readChunk() ?: break
        stream.close()
        return out.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    /** sync 프로토콜 SEND로 파일을 쓴다. [mode]는 adb처럼 파일 종류 비트를 포함한 st_mode(기본 일반 파일 0644). */
    suspend fun push(data: ByteArray, remotePath: String, mode: Int = 0x81A4, mtimeSeconds: Int = 0) {
        val stream = open("sync:")
        try {
            stream.write(syncRequest("SEND", "$remotePath,$mode".encodeToByteArray()))
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + SYNC_DATA_MAX, data.size)
                stream.write(syncRequest("DATA", data.copyOfRange(offset, end)))
                offset = end
            }
            stream.write(syncHeader("DONE", mtimeSeconds))
            val reply = stream.readFully(8)
            val id = reply.copyOf(4).decodeToString()
            val length = reply.intLe(4)
            when (id) {
                "OKAY" -> Unit
                "FAIL" -> throw AdbException("기기에 쓰지 못했습니다($remotePath): ${stream.readFully(length).decodeToString()}")
                else -> throw AdbProtocolException("sync 응답을 알 수 없습니다: $id")
            }
            stream.write(syncHeader("QUIT", 0))
        } finally {
            stream.close()
        }
    }

    suspend fun close() {
        fail(EndOfStreamException("연결을 닫았습니다"))
        channel.close()
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val message = readMessage(channel)
                when (message.command) {
                    AdbMessage.OKAY -> {
                        val local = message.arg1
                        // OPEN의 답이면 여기서 스트림을 등록한다. 기기가 OKAY 바로 뒤에 WRTE를 보내도 받을 곳이 있게.
                        val opened = lock.withLock {
                            opening.remove(local)?.let { waiting -> AdbStream(local, message.arg0).also { streams[local] = it } to waiting }
                        }
                        if (opened != null) opened.second.complete(opened.first) else lock.withLock { streams[local] }?.acknowledged()
                    }
                    AdbMessage.WRTE -> {
                        val stream = lock.withLock { streams[message.arg1] }
                        if (stream != null) {
                            stream.received(message.payload)
                            send(AdbMessage(AdbMessage.OKAY, message.arg1, message.arg0))
                        } else {
                            send(AdbMessage(AdbMessage.CLSE, 0, message.arg0))
                        }
                    }
                    AdbMessage.CLSE -> {
                        val local = message.arg1
                        lock.withLock { opening.remove(local) }?.completeExceptionally(OpenRefused())
                        lock.withLock { streams.remove(local) }?.closedByDevice()
                    }
                    else -> Unit // CNXN 재전송 등은 무시
                }
            }
        } catch (e: CancellationException) {
            fail(e)
            throw e
        } catch (e: Throwable) {
            fail(e)
        }
    }

    private suspend fun fail(cause: Throwable) {
        val (pending, open) = lock.withLock {
            if (failure == null) failure = cause
            val p = opening.values.toList()
            val s = streams.values.toList()
            streams.clear()
            p to s
        }
        pending.forEach { it.completeExceptionally(cause) }
        open.forEach { it.closedByDevice() }
        ended.complete(Unit)
    }

    private suspend fun send(message: AdbMessage) = writeLock.withLock { channel.writeMessage(message) }

    private class OpenRefused : Exception()

    /** 기기 서비스와 이어진 스트림. 읽기는 받은 순서대로, 쓰기는 [maxPayload] 단위로 나눠 보낸다. */
    inner class AdbStream internal constructor(private val localId: Int, private val remoteId: Int) : ByteSource, ByteSink {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var buffer = ByteArray(0)
        private val writeOne = Mutex()

        /** [closed]·[ack]는 읽기 루프와 쓰는 코루틴이 함께 보므로 이 락 안에서만 바꾼다. */
        private val state = Mutex()
        private var ack: CompletableDeferred<Unit>? = null
        private var closed = false

        internal fun received(bytes: ByteArray) {
            incoming.trySend(bytes)
        }

        internal suspend fun acknowledged() = state.withLock { ack?.complete(Unit) }

        internal suspend fun closedByDevice() {
            state.withLock {
                closed = true
                ack?.completeExceptionally(EndOfStreamException())
            }
            incoming.close()
        }

        /** 다음에 받은 덩어리. 스트림이 끝났으면 null. */
        suspend fun readChunk(): ByteArray? {
            if (buffer.isNotEmpty()) return buffer.also { buffer = ByteArray(0) }
            return incoming.receiveCatching().getOrNull()
        }

        override suspend fun readFully(count: Int): ByteArray {
            while (buffer.size < count) {
                val next = incoming.receiveCatching().getOrNull() ?: throw EndOfStreamException()
                buffer += next
            }
            return buffer.copyOf(count).also { buffer = buffer.copyOfRange(count, buffer.size) }
        }

        override suspend fun write(bytes: ByteArray) = writeOne.withLock {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + maxPayload, bytes.size)
                // 닫혔는지 확인과 ack 등록을 한 번에 한다. 그 사이에 닫히면 기다릴 OKAY가 영영 오지 않는다.
                val waiting = state.withLock {
                    if (closed) throw EndOfStreamException()
                    CompletableDeferred<Unit>().also { ack = it }
                }
                send(AdbMessage(AdbMessage.WRTE, localId, remoteId, bytes.copyOfRange(offset, end)))
                waiting.await()
                offset = end
            }
        }

        override suspend fun close() = withContext(NonCancellable) {
            val removed = lock.withLock { streams.remove(localId) } != null
            if (removed && failure == null) runCatching { send(AdbMessage(AdbMessage.CLSE, localId, remoteId)) }
            closedByDevice()
        }
    }

    companion object {
        private const val SYNC_DATA_MAX = 64 * 1024

        /** 기기에 알리는 기능. 이 클라이언트가 쓰는 서비스만 고른다. */
        private const val FEATURES = "cmd,stat_v2,ls_v2,fixed_push_mkdir"

        /**
         * CNXN을 보내고 AUTH를 마친다. 먼저 토큰에 서명하고, 기기가 그 키를 모르면(토큰을 다시 보냄) 공개키를 보낸다.
         * 공개키를 보내고 나면 [onWaitingForUser]가 불리고, 기기에 "USB 디버깅을 허용하시겠습니까?" 창이 뜬다. 허용할 때까지 기다리므로
         * 호출하는 쪽이 시간 제한을 둔다.
         */
        suspend fun connect(
            channel: AdbChannel,
            key: AdbRsaKey,
            keyName: String,
            scope: CoroutineScope,
            /** 연결 단계 기록(웹은 브라우저 콘솔로). */
            log: (String) -> Unit = {},
            onWaitingForUser: () -> Unit = {},
        ): AdbConnection {
            log("CNXN 보냄")
            channel.writeMessage(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::features=$FEATURES\u0000".encodeToByteArray()))
            var signed = false
            var sentKey = false
            while (true) {
                val message = readMessage(channel)
                log("받음 $message")
                when (message.command) {
                    AdbMessage.AUTH -> {
                        if (message.arg0 != AdbMessage.AUTH_TOKEN) throw AdbProtocolException("알 수 없는 AUTH 종류: ${message.arg0}")
                        val reply = when {
                            !signed -> AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, key.sign(message.payload)).also { signed = true }
                            !sentKey -> {
                                sentKey = true
                                AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0, (key.androidPublicKey(keyName) + "\u0000").encodeToByteArray())
                            }
                            else -> throw AdbException("기기가 이 키를 받아들이지 않았습니다")
                        }
                        channel.writeMessage(reply)
                        log("보냄 AUTH(${if (reply.arg0 == AdbMessage.AUTH_SIGNATURE) "서명" else "공개키"})")
                        // 공개키를 다 보낸 뒤에 알린다(보내는 중에 멈췄는지 기기가 허용을 기다리는지 구분되게).
                        if (reply.arg0 == AdbMessage.AUTH_RSAPUBLICKEY) onWaitingForUser()
                    }
                    AdbMessage.CNXN -> {
                        val banner = message.payload.decodeToString().trimEnd('\u0000')
                        val max = message.arg1.takeIf { it > 0 }?.coerceAtMost(AdbMessage.MAX_PAYLOAD) ?: 4096
                        return AdbConnection(channel, banner, max, scope)
                    }
                    else -> throw AdbProtocolException("연결 중 뜻밖의 메시지: ${AdbMessage.commandName(message.command)}")
                }
            }
        }

        /**
         * 헤더와 payload를 따로 쓴다. USB에서는 adbd가 24바이트 헤더와 payload를 각각 한 전송으로 받으므로,
         * 한 전송에 합쳐 보내면 기기 쪽 버퍼를 넘쳐 OUT 엔드포인트가 멈춘다(adb 호스트도 따로 보낸다). TCP는 상관없다.
         */
        internal suspend fun ByteSink.writeMessage(message: AdbMessage) {
            write(message.header())
            if (message.payload.isNotEmpty()) write(message.payload)
        }

        internal suspend fun readMessage(source: ByteSource): AdbMessage {
            val header = AdbMessage.decodeHeader(source.readFully(AdbMessage.HEADER_SIZE))
            val payload = if (header.length > 0) source.readFully(header.length) else ByteArray(0)
            return AdbMessage(header.command, header.arg0, header.arg1, payload)
        }

        private fun syncHeader(id: String, value: Int) = ByteArray(8).also {
            id.encodeToByteArray().copyInto(it)
            it.putIntLe(4, value)
        }

        private fun syncRequest(id: String, body: ByteArray) = syncHeader(id, body.size) + body
    }
}
