package io.github.eoeo0326.adbmirror.core.adb.protocol

import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 한 방향 바이트 통로. */
internal class Pipe {
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    var buffer = ByteArray(0)
    suspend fun readFully(count: Int): ByteArray {
        while (buffer.size < count) buffer += chunks.receiveCatching().getOrNull() ?: throw EndOfStreamException()
        return buffer.copyOf(count).also { buffer = buffer.copyOfRange(count, buffer.size) }
    }
}

internal class End(val inbox: Pipe, val outbox: Pipe) : AdbChannel {
    override suspend fun readFully(count: Int) = inbox.readFully(count)
    override suspend fun write(bytes: ByteArray) { outbox.chunks.send(bytes) }
    override suspend fun close() { outbox.chunks.close() }
}

/**
 * 기기 쪽 adbd 흉내. [knowsKey]가 false면 서명을 거절하고(토큰을 다시 보냄) 공개키를 받은 뒤 연결한다.
 * 서비스: `shell:echo hi`, `sync:`(파일 저장), `localabstract:…`(거절).
 */
internal class FakeAdbd(private val io: AdbChannel, private val knowsKey: Boolean) {
    val files = mutableMapOf<String, ByteArray>()
    val received = mutableListOf<String>()
    var publicKey: String? = null

    /** 닫힐 때까지 둔 스트림. 클라이언트가 CLSE를 보내면 [clientClosed]에 들어간다. */
    val closedByClient = mutableSetOf<Int>()
    val clientClosed = mutableListOf<Int>()

    fun start(scope: CoroutineScope) = scope.launch {
        val token = ByteArray(20) { it.toByte() }
        var nextId = 100
        val syncBuffers = mutableMapOf<Int, ByteArray>()
        val remoteOf = mutableMapOf<Int, Int>()
        val closeOnWrite = mutableSetOf<Int>()
        suspend fun send(cmd: Int, a0: Int, a1: Int, p: ByteArray = ByteArray(0)) = io.write(AdbMessage(cmd, a0, a1, p).encode())
        while (true) {
            val m = try { AdbConnection.readMessage(io) } catch (_: EndOfStreamException) { break }
            received += AdbMessage.commandName(m.command)
            when (m.command) {
                AdbMessage.CNXN -> send(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token)
                AdbMessage.AUTH -> when (m.arg0) {
                    AdbMessage.AUTH_SIGNATURE ->
                        if (knowsKey) send(AdbMessage.CNXN, AdbMessage.VERSION, 4096, "device::ro.product.model=SM-N976N;features=cmd\u0000".encodeToByteArray())
                        else send(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token)
                    AdbMessage.AUTH_RSAPUBLICKEY -> {
                        publicKey = m.payload.decodeToString().trimEnd('\u0000')
                        send(AdbMessage.CNXN, AdbMessage.VERSION, 4096, "device::ro.product.model=SM-N976N\u0000".encodeToByteArray())
                    }
                }
                AdbMessage.OPEN -> {
                    val service = m.payload.decodeToString().trimEnd('\u0000')
                    val local = m.arg0
                    if (service.startsWith("localabstract:")) {
                        send(AdbMessage.CLSE, 0, local)
                        continue
                    }
                    val id = nextId++
                    remoteOf[id] = local
                    send(AdbMessage.OKAY, id, local)
                    if (service == "shell:echo hi") {
                        // OKAY 바로 뒤에 출력이 온다(클라이언트가 등록 전이면 잃어버림).
                        send(AdbMessage.WRTE, id, local, "hi".encodeToByteArray())
                        send(AdbMessage.WRTE, id, local, "\n".encodeToByteArray())
                        send(AdbMessage.CLSE, id, local)
                    } else if (service == "sync:") {
                        syncBuffers[id] = ByteArray(0)
                    } else if (service == "shell:logcat") {
                        // 오래 도는 명령: 두 줄을 보내고 클라이언트가 닫을 때까지 둔다.
                        send(AdbMessage.WRTE, id, local, "line 1\nline".encodeToByteArray())
                        send(AdbMessage.WRTE, id, local, " 2\n".encodeToByteArray())
                        closedByClient.add(id)
                    } else if (service == "close-on-write:") {
                        closeOnWrite += id
                    }
                }
                AdbMessage.WRTE -> {
                    val id = m.arg1
                    if (id in closeOnWrite) {
                        send(AdbMessage.CLSE, id, m.arg0) // OKAY 없이 닫는다
                        continue
                    }
                    send(AdbMessage.OKAY, id, m.arg0)
                    val buf = (syncBuffers[id] ?: continue) + m.payload
                    syncBuffers[id] = buf
                    // SEND path,mode / DATA… / DONE 이 다 모이면 저장하고 OKAY
                    val done = findDone(buf) ?: continue
                    val spec = buf.copyOfRange(8, 8 + buf.intLe(4)).decodeToString()
                    var p = 8 + buf.intLe(4)
                    var data = ByteArray(0)
                    while (p < done) {
                        val len = buf.intLe(p + 4)
                        data += buf.copyOfRange(p + 8, p + 8 + len)
                        p += 8 + len
                    }
                    files[spec] = data
                    syncBuffers[id] = buf.copyOfRange(done + 8, buf.size)
                    send(AdbMessage.WRTE, id, m.arg0, "OKAY".encodeToByteArray() + ByteArray(4))
                }
                AdbMessage.CLSE -> if (m.arg1 in closedByClient) clientClosed += m.arg1
                AdbMessage.OKAY -> Unit
            }
        }
    }

    /** SEND 뒤 DATA들을 건너뛰어 DONE 위치를 찾는다. 아직 다 안 왔으면 null. */
    private fun findDone(buf: ByteArray): Int? {
        if (buf.size < 8) return null
        var p = 8 + buf.intLe(4)
        while (p + 8 <= buf.size) {
            when (buf.copyOfRange(p, p + 4).decodeToString()) {
                "DATA" -> p += 8 + buf.intLe(p + 4)
                "DONE" -> return p
                else -> return null
            }
        }
        return null
    }
}

/** 메모리 통로로 이은 클라이언트 쪽 [AdbChannel]과 [FakeAdbd]. */
internal fun fakeDevice(scope: CoroutineScope, knowsKey: Boolean = true): Pair<AdbChannel, FakeAdbd> {
    val toDevice = Pipe()
    val toHost = Pipe()
    val device = FakeAdbd(End(toDevice, toHost), knowsKey)
    device.start(scope)
    return End(toHost, toDevice) to device
}
