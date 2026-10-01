package io.github.eoeo0326.adbmirror.core.adb.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdbMessageTest {
    @Test
    fun commandsAreAsciiLittleEndian() {
        assertEquals("CNXN", AdbMessage.commandName(AdbMessage.CNXN))
        assertEquals("AUTH", AdbMessage.commandName(AdbMessage.AUTH))
        assertEquals("OPEN", AdbMessage.commandName(AdbMessage.OPEN))
        assertEquals("OKAY", AdbMessage.commandName(AdbMessage.OKAY))
        assertEquals("CLSE", AdbMessage.commandName(AdbMessage.CLSE))
        assertEquals("WRTE", AdbMessage.commandName(AdbMessage.WRTE))
    }

    @Test
    fun encodesHeaderLikeAdb() {
        // adb의 첫 CNXN: version 0x01000001, maxdata 256KiB, "host::\0"
        val payload = "host::\u0000".encodeToByteArray()
        val bytes = AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, payload).encode()
        val expectedHeader = byteArrayOf(
            0x43, 0x4e, 0x58, 0x4e, // CNXN
            0x01, 0x00, 0x00, 0x01, // version
            0x00, 0x00, 0x04, 0x00, // 262144
            0x07, 0x00, 0x00, 0x00, // length
            0x32, 0x02, 0x00, 0x00, // checksum = 'h'+'o'+'s'+'t'+':'+':' = 562
            0xbc.toByte(), 0xb1.toByte(), 0xa7.toByte(), 0xb1.toByte(), // magic
        )
        assertContentEquals(expectedHeader, bytes.copyOf(24))
        assertContentEquals(payload, bytes.copyOfRange(24, bytes.size))
    }

    @Test
    fun decodesHeaderAndRejectsBadMagicOrLength() {
        val msg = AdbMessage(AdbMessage.WRTE, 7, 0x80000001.toInt(), ByteArray(5) { 1 })
        val h = AdbMessage.decodeHeader(msg.header())
        assertEquals(AdbMessage.WRTE, h.command)
        assertEquals(7, h.arg0)
        assertEquals(0x80000001.toInt(), h.arg1)
        assertEquals(5, h.length)
        assertEquals(5, h.checksum)

        val badMagic = msg.header().also { it[20] = 0 }
        assertFailsWith<AdbProtocolException> { AdbMessage.decodeHeader(badMagic) }
        val tooLong = msg.header().also { it.putIntLe(12, AdbMessage.MAX_PAYLOAD + 1) }
        assertFailsWith<AdbProtocolException> { AdbMessage.decodeHeader(tooLong) }
    }
}
