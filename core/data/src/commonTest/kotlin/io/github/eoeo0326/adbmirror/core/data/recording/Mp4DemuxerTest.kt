package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Mp4DemuxerTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val sps = b(0x67, 0x64, 0x00, 0x1F, 0xAC)
    private val pps = b(0x68, 0xEE, 0x3C)
    private val config = EncodedPacket(EncodedPacket.Kind.Config, null, b(0, 0, 0, 1) + sps + b(0, 0, 0, 1) + pps)
    private fun key(pts: Long, tag: Int) = EncodedPacket(EncodedPacket.Kind.KeyFrame, pts, b(0, 0, 0, 1, 0x65, tag))
    private fun frame(pts: Long, tag: Int) = EncodedPacket(EncodedPacket.Kind.Frame, pts, b(0, 0, 0, 1, 0x41, tag))

    /** 우리 muxer로 만든 녹화 파일: key(1s) · frame · frame · key · frame, 33ms 간격(마지막 조각은 finish). */
    private fun recording(): ByteArray {
        val m = Mp4Muxer(VideoSize(340, 720), config)
        var out = m.header()
        val packets = listOf(key(1_000_000, 1), frame(1_033_000, 2), frame(1_066_000, 3), key(1_100_000, 4), frame(1_133_000, 5))
        packets.forEach { p -> m.addFrame(p)?.let { out += it } }
        m.finish()?.let { out += it }
        return out
    }

    private fun demux(bytes: ByteArray) = Mp4Demuxer.read(bytes.size.toLong()) { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }

    @Test
    fun readsTrackHeaderAndCodec() {
        val track = demux(recording())
        assertEquals(340, track.width)
        assertEquals(720, track.height)
        assertEquals("avc1.64001f", track.codec)
        assertContentEquals(b(1, 0x64, 0x00, 0x1F, 0xFF, 0xE1, 0, sps.size) + sps + b(1, 0, pps.size) + pps, track.avcC)
    }

    @Test
    fun samplesPointAtAvccDataWithTimesAndKeyFlags() {
        val bytes = recording()
        val track = demux(bytes)
        assertEquals(listOf(0L, 33_000L, 66_000L, 100_000L, 133_000L), track.samples.map { it.ptsUs })
        assertEquals(listOf(true, false, false, true, false), track.samples.map { it.key })
        // 각 샘플: 4바이트 길이 + NAL(헤더, 태그)
        track.samples.forEachIndexed { i, s ->
            val data = bytes.copyOfRange(s.offset.toInt(), s.offset.toInt() + s.size)
            assertEquals(6, s.size)
            assertEquals(i + 1, data.last().toInt())
        }
        // 마지막 샘플 길이는 앞 간격을 이어 쓴다
        assertEquals(VideoInfo(166, 340, 720), track.info)
    }

    @Test
    fun truncatedRecordingKeepsCompleteSamples() {
        val bytes = recording()
        val track = demux(bytes.copyOfRange(0, bytes.size - 3)) // 마지막 샘플 도중 끊김
        assertEquals(4, track.samples.size)
    }

    @Test
    fun fileWithoutFramesIsRejected() {
        val header = Mp4Muxer(VideoSize(340, 720), config).header()
        assertFailsWith<IllegalArgumentException> { demux(header) }
    }
}
