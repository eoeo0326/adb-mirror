package io.github.eoeo0326.adbmirror.feature.mirror.video

import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.data.scrcpy.VideoStreamParser
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BGRA 바이트를 PNG로 저장해 색 순서를 눈으로 확인할 수 있게 한다. */
private fun savePng(w: Int, h: Int, bgra: ByteArray, file: File) {
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (p in 0 until w * h) {
        val b = bgra[p * 4].toInt() and 0xFF
        val g = bgra[p * 4 + 1].toInt() and 0xFF
        val r = bgra[p * 4 + 2].toInt() and 0xFF
        img.setRGB(p % w, p / w, (r shl 16) or (g shl 8) or b)
    }
    javax.imageio.ImageIO.write(img, "png", file)
}

/** fixture(회전 포함) 패킷을 FFmpeg로 디코딩해 프레임 수와 해상도 전환을 확인한다. */
class FfmpegH264DecoderTest {
    @Test
    fun decodesFixtureAcrossRotation() = runBlocking {
        val dir = File(System.getProperty("fixtures.dir")!!)
        val parser = VideoStreamParser(ByteArraySource(File(dir, "scrcpy-v4.1-h264-rotate.bin").readBytes()))
        parser.readHeader()
        val sizes = mutableListOf<Pair<Int, Int>>()
        var nonBlack = 0
        FfmpegH264Decoder().use { decoder ->
            try {
                while (true) {
                    val item = parser.readItem() as? VideoStreamParser.Item.Packet ?: continue
                    decoder.decode(item.packet.data) { w, h, bgra ->
                        sizes += w to h
                        // 가운데 픽셀이 완전히 검지 않으면 실제 화면이 디코딩된 것으로 본다.
                        val i = ((h / 2) * w + w / 2) * 4
                        if ((bgra[i].toInt() and 0xFF) + (bgra[i + 1].toInt() and 0xFF) + (bgra[i + 2].toInt() and 0xFF) > 30) nonBlack++
                        if (sizes.size == 20) savePng(w, h, bgra, File("build/decoded-frame20.png"))
                    }
                }
            } catch (_: EndOfStreamException) {
            }
        }
        // low-delay 디코더라 프레임마다 바로 나온다. 세션 경계에서 몇 장이 빠질 수는 있다.
        assertTrue(sizes.size >= 120, "디코딩된 프레임 ${sizes.size}")
        assertEquals(listOf(340 to 720, 720 to 340, 340 to 720), sizes.distinct().let { d -> sizes.fold(mutableListOf<Pair<Int, Int>>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc } })
        assertTrue(nonBlack > sizes.size * 0.9, "검지 않은 프레임 $nonBlack / ${sizes.size}")
    }
}
