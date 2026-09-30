package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class VideoStreamParserTest {
    private fun u32(v: Long) = ByteArray(4) { ((v ushr (24 - 8 * it)) and 0xFF).toByte() }
    private fun u64(v: ULong) = ByteArray(8) { ((v shr (56 - 8 * it)) and 0xFFUL).toByte() }

    private fun session(w: Long, h: Long) = u32(0x80000000) + u32(w) + u32(h)
    private fun packet(flagsAndPts: ULong, payload: ByteArray) = u64(flagsAndPts) + u32(payload.size.toLong()) + payload

    private val stream: ByteArray =
        "Pixel".encodeToByteArray().copyOf(64) +
            u32(VideoStreamParser.CODEC_H264) +
            session(340, 720) +
            packet(1UL shl 62, byteArrayOf(0, 0, 0, 1, 0x67)) +
            packet((1UL shl 61) or 1_000UL, byteArrayOf(0, 0, 0, 1, 0x65, 7)) +
            packet(2_000UL, byteArrayOf(0, 0, 0, 1, 0x41))

    @Test
    fun parsesHeaderSessionAndPackets() = runTest {
        val parser = VideoStreamParser(ByteArraySource(stream))
        assertEquals(VideoStreamParser.Header("Pixel", VideoStreamParser.CODEC_H264), parser.readHeader())

        assertEquals(VideoStreamParser.Item.Session(VideoSize(340, 720)), parser.readItem())

        val config = assertIs<VideoStreamParser.Item.Packet>(parser.readItem()).packet
        assertEquals(EncodedPacket.Kind.Config, config.kind)
        assertNull(config.ptsUs)

        val key = assertIs<VideoStreamParser.Item.Packet>(parser.readItem()).packet
        assertEquals(EncodedPacket.Kind.KeyFrame, key.kind)
        assertEquals(1_000L, key.ptsUs)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x65, 7), key.data)

        val frame = assertIs<VideoStreamParser.Item.Packet>(parser.readItem()).packet
        assertEquals(EncodedPacket.Kind.Frame, frame.kind)
        assertEquals(2_000L, frame.ptsUs)

        assertFailsWith<EndOfStreamException> { parser.readItem() }
    }

    @Test
    fun truncatedPayloadEndsStream() = runTest {
        val parser = VideoStreamParser(ByteArraySource(stream.copyOf(stream.size - 2)))
        parser.readHeader()
        repeat(3) { parser.readItem() }
        assertFailsWith<EndOfStreamException> { parser.readItem() }
    }
}
