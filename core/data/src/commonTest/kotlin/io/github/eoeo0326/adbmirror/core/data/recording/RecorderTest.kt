package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecorderTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val sps = b(0x67, 0x42, 0x00, 0x1f, 0xda)
    private val sps2 = b(0x67, 0x42, 0x00, 0x1f, 0xdb)
    private val pps = b(0x68, 0xce, 0x3c, 0x80)
    private val portrait = VideoSize(340, 720)
    private val landscape = VideoSize(720, 340)
    private fun config(size: VideoSize?, s: ByteArray = sps) =
        EncodedPacket(EncodedPacket.Kind.Config, null, b(0, 0, 0, 1) + s + b(0, 0, 0, 1) + pps, size)
    private fun key(pts: Long) = EncodedPacket(EncodedPacket.Kind.KeyFrame, pts, b(0, 0, 0, 1, 0x65, 1, 2, 3))
    private fun frame(pts: Long) = EncodedPacket(EncodedPacket.Kind.Frame, pts, b(0, 0, 0, 1, 0x41, 9))

    private class MemPart(override val path: String) : RecordingPart {
        var bytes = ByteArray(0)
        var closed = false
        override fun write(bytes: ByteArray) { this.bytes += bytes }
        override fun close() { closed = true }
    }

    private val parts = mutableListOf<MemPart>()
    private val recorder = Recorder { i -> MemPart("p$i").also { parts += it } }

    private fun samplesIn(part: MemPart): Int = readBoxes(part.bytes).filter { it.type == "moof" }.sumOf { moof ->
        val trun = listOf(moof).find("moof/traf/trun")
        trun.u32(trun.bodyStart + 4).toInt()
    }

    @Test
    fun opensFileOnlyAtFirstKeyFrameAfterConfig() {
        recorder.accept(frame(1)) // config 전
        recorder.accept(config(portrait))
        recorder.accept(frame(2)) // key frame 전
        assertTrue(parts.isEmpty())
        recorder.accept(key(10))
        recorder.accept(frame(20))
        val result = recorder.finish()
        assertEquals(listOf("p1"), result.files)
        assertEquals(2, samplesIn(parts[0]))
        assertTrue(parts[0].closed)
    }

    @Test
    fun sameConfigContinuesSamePart() {
        recorder.accept(config(portrait))
        recorder.accept(key(0))
        recorder.accept(frame(33_000))
        recorder.accept(config(portrait)) // key frame 재요청
        recorder.accept(key(66_000))
        recorder.accept(frame(99_000))
        assertEquals(listOf("p1"), recorder.finish().files)
        assertEquals(4, samplesIn(parts[0]))
    }

    @Test
    fun sizeOrConfigChangeStartsNewPart() {
        recorder.accept(config(portrait))
        recorder.accept(key(0))
        recorder.accept(frame(1_000_000))
        recorder.accept(config(landscape))
        recorder.accept(key(1_500_000))
        recorder.accept(frame(2_000_000))
        recorder.accept(config(landscape, sps2))
        recorder.accept(key(2_500_000))
        val result = recorder.finish()
        assertEquals(listOf("p1", "p2", "p3"), result.files)
        assertEquals(listOf(2, 2, 1), parts.map(::samplesIn))
        assertTrue(parts.all { it.closed })
        assertEquals(1500, result.durationMs) // part마다 첫~마지막 프레임: 1000 + 500 + 0
    }

    @Test
    fun dropsFramesWhosePtsDoesNotAdvance() {
        recorder.accept(config(portrait))
        recorder.accept(key(100))
        recorder.accept(frame(100))
        recorder.accept(frame(50))
        recorder.accept(frame(200))
        recorder.finish()
        assertEquals(2, samplesIn(parts[0]))
    }

    @Test
    fun configWithoutSizeIsIgnoredAndEmptyRecordingHasNoFiles() {
        recorder.accept(config(null))
        recorder.accept(key(0))
        assertEquals(Recorder.Result(emptyList(), 0), recorder.finish())
    }
}
