package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Mp4MuxerTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val sps = b(0x67, 0x64, 0x00, 0x1F, 0xAC)
    private val pps = b(0x68, 0xEE, 0x3C)
    private val config = EncodedPacket(EncodedPacket.Kind.Config, null, b(0, 0, 0, 1) + sps + b(0, 0, 0, 1) + pps)
    private fun key(pts: Long) = EncodedPacket(EncodedPacket.Kind.KeyFrame, pts, b(0, 0, 0, 1, 0x65, 1, 2, 3))
    private fun frame(pts: Long) = EncodedPacket(EncodedPacket.Kind.Frame, pts, b(0, 0, 0, 1, 0x41, 9))

    private fun muxer() = Mp4Muxer(VideoSize(340, 720), config)

    @Test
    fun headerHasFtypAndMoovWithAvcC() {
        val header = muxer().header()
        val boxes = readBoxes(header)
        assertEquals(listOf("ftyp", "moov"), boxes.map { it.type })
        val avcC = boxes.find("moov/trak/mdia/minf/stbl/stsd/avc1")
            .let { avc1 -> readBoxes(header, avc1.bodyStart + 78, avc1.end).single() }
        assertEquals("avcC", avcC.type)
        val body = header.copyOfRange(avcC.bodyStart, avcC.end)
        assertContentEquals(b(1, 0x64, 0x00, 0x1F, 0xFF, 0xE1, 0, sps.size) + sps + b(1, 0, pps.size) + pps, body)
        val tkhd = boxes.find("moov/trak/tkhd")
        assertEquals(340L shl 16, tkhd.u32(tkhd.end - 8))
        assertEquals(720L shl 16, tkhd.u32(tkhd.end - 4))
    }

    @Test
    fun framesBeforeFirstKeyFrameAreDropped() {
        val m = muxer().also { it.header() }
        assertNull(m.addFrame(frame(0)))
        assertNull(m.finish())
    }

    @Test
    fun keyFrameClosesFragmentAndOffsetsPointIntoMdat() {
        val m = muxer().also { it.header() }
        assertNull(m.addFrame(key(1_000_000)))
        assertNull(m.addFrame(frame(1_033_000)))
        val first = assertNotNull(m.addFrame(key(1_066_000)))
        val last = assertNotNull(m.finish())

        val boxes = readBoxes(first)
        assertEquals(listOf("moof", "mdat"), boxes.map { it.type })
        val moof = boxes[0]
        val trun = boxes.find("moof/traf/trun")
        assertEquals(2, trun.u32(trun.bodyStart + 4).toInt()) // sample_count
        val dataOffset = trun.u32(trun.bodyStart + 8).toInt()
        assertEquals(boxes[1].bodyStart - moof.start, dataOffset)
        // 첫 샘플: duration 33,000µs, size 4+4, sync flags
        assertEquals(33_000L, trun.u32(trun.bodyStart + 12))
        assertEquals(8L, trun.u32(trun.bodyStart + 16))
        assertEquals(0x02000000L, trun.u32(trun.bodyStart + 20))
        assertEquals(0x01010000L, trun.u32(trun.bodyStart + 32))
        assertContentEquals(b(0, 0, 0, 4, 0x65, 1, 2, 3, 0, 0, 0, 2, 0x41, 9), first.copyOfRange(boxes[1].bodyStart, boxes[1].end))

        val tfdt = readBoxes(last).find("moof/traf/tfdt")
        assertEquals(66_000L, tfdt.u64(tfdt.bodyStart + 4)) // 첫 PTS 기준 상대 시간
        val mfhd = readBoxes(last).find("moof/mfhd")
        assertEquals(2L, mfhd.u32(mfhd.bodyStart + 4))
    }

    @Test
    fun longRunWithoutKeyFrameIsSplitByDuration() {
        val m = Mp4Muxer(VideoSize(340, 720), config, fragmentDurationUs = 100_000).also { it.header() }
        m.addFrame(key(0))
        val fragments = (1..10).mapNotNull { m.addFrame(frame(it * 33_000L)) }
        assertEquals(2, fragments.size)
    }

    @Test
    fun avccDropsParameterSetsAndPrefixesLength() {
        val out = Mp4Muxer.toAvcc(b(0, 0, 0, 1) + sps + b(0, 0, 1, 0x65, 7))
        assertContentEquals(b(0, 0, 0, 2, 0x65, 7), out)
    }

    @Test
    fun configMustContainSpsAndPps() {
        assertFailsWith<IllegalStateException> {
            Mp4Muxer(VideoSize(1, 1), EncodedPacket(EncodedPacket.Kind.Config, null, b(0, 0, 1, 0x68, 1)))
        }
        assertFailsWith<IllegalArgumentException> { Mp4Muxer(VideoSize(1, 1), key(0)) }
    }
}
