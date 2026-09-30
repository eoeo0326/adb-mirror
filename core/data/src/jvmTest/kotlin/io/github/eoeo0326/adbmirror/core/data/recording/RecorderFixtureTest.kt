package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.data.scrcpy.VideoStreamParser
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * fixture 전체(세로 → 가로 → 세로)를 녹화하면 part 3개가 된다. 결과는 build/recorder-parts/에 남겨
 * `scripts/check_mp4.swift`로 재생 가능 여부를 확인할 수 있게 한다.
 */
class RecorderFixtureTest {
    private val dir = File(System.getProperty("fixtures.dir")!!)

    @Test
    fun rotationsSplitIntoPlayableParts() = runTest {
        val out = File("build/recorder-parts").apply { deleteRecursively(); mkdirs() }
        val recorder = Recorder { i ->
            val file = File(out, "part$i.mp4")
            object : RecordingPart {
                val stream = file.outputStream()
                override val path = file.path
                override fun write(bytes: ByteArray) = stream.write(bytes)
                override fun close() = stream.close()
            }
        }
        val parser = VideoStreamParser(ByteArraySource(File(dir, "scrcpy-v4.1-h264-rotate.bin").readBytes()))
        parser.readHeader()
        var size: VideoSize? = null
        val framesPerSession = mutableListOf<Int>()
        try {
            while (true) {
                when (val item = parser.readItem()) {
                    is VideoStreamParser.Item.Session -> { size = item.size; framesPerSession += 0 }
                    is VideoStreamParser.Item.Packet -> {
                        val p = item.packet
                        // ScrcpyMirrorSession과 같이 config에 크기를 싣는다
                        recorder.accept(if (p.kind == EncodedPacket.Kind.Config) EncodedPacket(p.kind, null, p.data, size) else p)
                        if (p.kind != EncodedPacket.Kind.Config) framesPerSession[framesPerSession.lastIndex]++
                    }
                }
            }
        } catch (_: EndOfStreamException) {
        }
        val result = recorder.finish()

        assertEquals(3, result.files.size)
        val samples = result.files.map { path ->
            val boxes = readBoxes(File(path).readBytes())
            assertEquals(listOf("ftyp", "moov"), boxes.take(2).map { it.type })
            boxes.filter { it.type == "moof" }.sumOf { moof ->
                val trun = listOf(moof).find("moof/traf/trun")
                trun.u32(trun.bodyStart + 4).toInt()
            }
        }
        assertEquals(framesPerSession, samples)
    }
}
