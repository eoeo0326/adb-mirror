package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.data.conversion.FfmpegVideoFrameSource
import io.github.eoeo0326.adbmirror.core.data.scrcpy.VideoStreamParser
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * fixture의 첫 세션(세로 340×720)을 MP4로 만든다. 결과 파일은 build/fixture-mp4/에 남겨
 * 플레이어·ffprobe로 직접 열어 볼 수 있게 한다.
 */
class FixtureMp4Test {
    private val dir = File(System.getProperty("fixtures.dir")!!)

    @Test
    fun firstSessionBecomesValidFragmentedMp4() = runTest {
        val parser = VideoStreamParser(ByteArraySource(File(dir, "scrcpy-v4.1-h264-rotate.bin").readBytes()))
        parser.readHeader()
        var size: VideoSize? = null
        var muxer: Mp4Muxer? = null
        val out = java.io.ByteArrayOutputStream()
        var frames = 0
        try {
            loop@ while (true) {
                when (val item = parser.readItem()) {
                    is VideoStreamParser.Item.Session -> if (size == null) size = item.size else break@loop
                    is VideoStreamParser.Item.Packet -> {
                        val p = item.packet
                        if (p.kind == EncodedPacket.Kind.Config) {
                            if (muxer == null) muxer = Mp4Muxer(size!!, p).also { out.write(it.header()) }
                        } else {
                            frames++
                            muxer!!.addFrame(p)?.let(out::write)
                        }
                    }
                }
            }
        } catch (_: EndOfStreamException) {
        }
        muxer!!.finish()?.let(out::write)

        val bytes = out.toByteArray()
        val boxes = readBoxes(bytes)
        assertEquals(listOf("ftyp", "moov"), boxes.take(2).map { it.type })
        val fragments = boxes.drop(2)
        assertEquals(fragments.size, fragments.count { it.type == "moof" } * 2)
        val samples = fragments.filter { it.type == "moof" }.sumOf { moof ->
            val trun = listOf(moof).find("moof/traf/trun")
            trun.u32(trun.bodyStart + 4).toInt()
        }
        assertEquals(70, frames) // fixture JSON: 두 번째 세션의 frameIndex
        assertEquals(frames, samples)

        File("build/fixture-mp4").mkdirs()
        val file = File("build/fixture-mp4/session1.mp4").apply { writeBytes(bytes) }

        // 웹 변환이 쓰는 Mp4Demuxer가 Desktop(FFmpeg)과 같은 샘플 수·크기·길이를 읽는지 본다.
        val track = Mp4Demuxer.read(bytes.size.toLong()) { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
        assertEquals(frames, track.samples.size)
        assertEquals(FfmpegVideoFrameSource().info(file.path), track.info)
    }
}
