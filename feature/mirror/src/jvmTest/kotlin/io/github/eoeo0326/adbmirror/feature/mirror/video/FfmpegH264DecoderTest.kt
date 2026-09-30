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

    /** 20번째 프레임을 디코딩해 (크기, BGRA)를 돌려준다. */
    private fun frame20(hardware: Boolean): Triple<Int, Int, ByteArray> = runBlocking {
        val dir = File(System.getProperty("fixtures.dir")!!)
        val parser = VideoStreamParser(ByteArraySource(File(dir, "scrcpy-v4.1-h264-rotate.bin").readBytes()))
        parser.readHeader()
        var n = 0
        var result: Triple<Int, Int, ByteArray>? = null
        FfmpegH264Decoder(hardware).use { decoder ->
            while (result == null) {
                val item = parser.readItem() as? VideoStreamParser.Item.Packet ?: continue
                decoder.decode(item.packet.data) { w, h, bgra -> if (++n == 20) result = Triple(w, h, bgra.copyOf()) }
            }
        }
        result!!
    }

    /**
     * 하드웨어 디코더(없으면 소프트웨어로 되돌아감)와 소프트웨어 디코더가 같은 화면을 낸다.
     * fixture 너비 340은 16의 배수가 아니라, 변환 너비를 늘렸다가 원래 너비만 복사하는 경로도 함께 확인한다.
     */
    @Test
    fun hardwareAndSoftwareDecodeMatch() {
        val (hw, hh, hwBgra) = frame20(hardware = true)
        val (sw, sh, swBgra) = frame20(hardware = false)
        assertEquals(sw to sh, hw to hh)
        assertEquals(340 to 720, sw to sh)
        // 색 변환 반올림 차이만 허용: 채널 평균 차이 2 이하
        var diff = 0L
        for (i in swBgra.indices) diff += kotlin.math.abs((hwBgra[i].toInt() and 0xFF) - (swBgra[i].toInt() and 0xFF))
        val mean = diff.toDouble() / swBgra.size
        assertTrue(mean <= 2.0, "평균 채널 차이 $mean")
        // 오른쪽 끝 열이 늘린 너비의 쓰레기가 아니라 옆 열과 비슷해야 한다(줄 밀림 없음)
        var edge = 0L
        for (y in 0 until sh) {
            val last = (y * sw + sw - 1) * 4
            val prev = last - 4
            for (c in 0 until 3) edge += kotlin.math.abs((swBgra[last + c].toInt() and 0xFF) - (swBgra[prev + c].toInt() and 0xFF))
        }
        assertTrue(edge.toDouble() / (sh * 3) < 20.0, "오른쪽 끝 열 차이 ${edge.toDouble() / (sh * 3)}")
    }

    /** 하드웨어 장치를 붙인 채 열기가 실패하면 소프트웨어로 다시 열어 정상 디코딩한다. */
    @Test
    fun fallsBackToSoftwareWhenHardwareOpenFails() = runBlocking {
        var hardwareAttempts = 0
        val decoder = FfmpegH264Decoder(hardware = true) { ctx, codec ->
            if (ctx.hw_device_ctx() != null) {
                hardwareAttempts++
                -1 // 하드웨어 열기 실패를 흉내 낸다
            } else {
                org.bytedeco.ffmpeg.global.avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
            }
        }
        decoder.use {
            assertEquals("software", it.backend)
            val dir = File(System.getProperty("fixtures.dir")!!)
            val parser = VideoStreamParser(ByteArraySource(File(dir, "scrcpy-v4.1-h264-rotate.bin").readBytes()))
            parser.readHeader()
            var frames = 0
            while (frames < 5) {
                val item = parser.readItem() as? VideoStreamParser.Item.Packet ?: continue
                it.decode(item.packet.data) { _, _, _ -> frames++ }
            }
        }
        // 하드웨어 장치가 없는 러너(Linux CI)에서는 시도 자체가 없다.
        println("hardware open attempts=$hardwareAttempts")
    }
}
