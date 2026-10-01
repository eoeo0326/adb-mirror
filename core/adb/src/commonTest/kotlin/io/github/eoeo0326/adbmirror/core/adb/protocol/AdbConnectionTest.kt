package io.github.eoeo0326.adbmirror.core.adb.protocol

import io.github.eoeo0326.adbmirror.core.adb.AdbException
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.adb.SocketNotReadyException
import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKey
import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKeyVectorKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbConnectionTest {
    /** 한 방향 바이트 통로. */
    private class Pipe {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        var buffer = ByteArray(0)
        suspend fun readFully(count: Int): ByteArray {
            while (buffer.size < count) buffer += chunks.receiveCatching().getOrNull() ?: throw EndOfStreamException()
            return buffer.copyOf(count).also { buffer = buffer.copyOfRange(count, buffer.size) }
        }
    }

    private class End(val inbox: Pipe, val outbox: Pipe) : AdbChannel {
        override suspend fun readFully(count: Int) = inbox.readFully(count)
        override suspend fun write(bytes: ByteArray) { outbox.chunks.send(bytes) }
        override suspend fun close() { outbox.chunks.close() }
    }

    /**
     * 기기 쪽 adbd 흉내. [knowsKey]가 false면 서명을 거절하고(토큰을 다시 보냄) 공개키를 받은 뒤 연결한다.
     * 서비스: `shell:echo hi`, `sync:`(파일 저장), `localabstract:…`(거절).
     */
    private class FakeAdbd(private val io: AdbChannel, private val knowsKey: Boolean) {
        val files = mutableMapOf<String, ByteArray>()
        val received = mutableListOf<String>()
        var publicKey: String? = null

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
                    AdbMessage.OKAY, AdbMessage.CLSE -> Unit
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

    private val key = AdbRsaKey(hex(AdbRsaKeyVectorKey.N), hex(AdbRsaKeyVectorKey.D))

    private fun TestScope.setUp(knowsKey: Boolean = true): Pair<AdbChannel, FakeAdbd> {
        val toDevice = Pipe()
        val toHost = Pipe()
        val device = FakeAdbd(End(toDevice, toHost), knowsKey)
        device.start(backgroundScope)
        return End(toHost, toDevice) to device
    }

    @Test
    fun knownKeyConnectsWithSignatureOnly() = runTest {
        val (host, device) = setUp()
        var prompted = false
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope) { prompted = true }
        assertEquals("SM-N976N", c.model)
        assertEquals(listOf("CNXN", "AUTH"), device.received)
        assertEquals(false, prompted)
    }

    @Test
    fun unknownKeySendsPublicKeyAndWaitsForUser() = runTest {
        val (host, device) = setUp(knowsKey = false)
        var prompted = false
        AdbConnection.connect(host, key, "me@test", backgroundScope) { prompted = true }
        assertTrue(prompted)
        assertTrue(device.publicKey!!.endsWith(" me@test"))
    }

    @Test
    fun shellCollectsOutputSentRightAfterOkay() = runTest {
        val (host, _) = setUp()
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope)
        assertEquals("hi\n", c.shell("echo hi"))
    }

    @Test
    fun pushSendsFileInChunksSmallerThanMaxPayload() = runTest {
        val (host, device) = setUp()
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope)
        val data = ByteArray(10_000) { (it % 251).toByte() } // 기기 maxdata 4096보다 커서 WRTE 여러 번
        c.push(data, "/data/local/tmp/x.jar")
        assertContentEquals(data, device.files["/data/local/tmp/x.jar,33188"])
    }

    @Test
    fun refusedLocalAbstractIsSocketNotReady() = runTest {
        val (host, _) = setUp()
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope)
        assertFailsWith<SocketNotReadyException> { c.open("localabstract:scrcpy_1") }
        assertFailsWith<AdbException> { c.open("localabstract:scrcpy_1") }
        assertEquals("hi\n", c.shell("echo hi"), "거절 뒤에도 연결은 쓸 수 있다")
    }

    @Test
    fun writeFailsInsteadOfHangingWhenDeviceClosesStream() = runTest {
        val (host, _) = setUp()
        val c = AdbConnection.connect(host, key, "me@test", backgroundScope)
        val stream = c.open("close-on-write:")
        assertFailsWith<EndOfStreamException> { stream.write(ByteArray(10_000)) }
        assertFailsWith<EndOfStreamException> { stream.write(ByteArray(1)) }
        assertEquals("hi\n", c.shell("echo hi"))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
