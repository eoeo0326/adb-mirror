package io.github.eoeo0326.adbmirror.feature.mirror.video

import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.data.scrcpy.VideoStreamParser
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YUV 평면을 셰이더로 그린 결과가 swscale BGRA 변환과 같은지 본다. CPU(래스터) Skia에서 같은 SkSL을 돌린다.
 * fixture 너비 340은 16의 배수가 아니고 색 평면 너비 170도 짝이 맞지 않아 stride 처리도 함께 확인한다.
 */
class YuvRendererTest {
    private fun packets(): Sequence<ByteArray> = sequence {
        val parser = VideoStreamParser(ByteArraySource(File(System.getProperty("fixtures.dir")!!, "scrcpy-v4.1-h264-rotate.bin").readBytes()))
        runBlocking { parser.readHeader() }
        while (true) {
            val item = runCatching { runBlocking { parser.readItem() } }.getOrNull() ?: break
            if (item is VideoStreamParser.Item.Packet) yield(item.packet.data)
        }
    }

    private fun bgraFrame(n: Int, hardware: Boolean): Triple<Int, Int, ByteArray> {
        var i = 0
        var out: Triple<Int, Int, ByteArray>? = null
        FfmpegH264Decoder(hardware).use { d ->
            for (p in packets()) {
                d.decode(p) { w, h, bgra -> if (++i == n) out = Triple(w, h, bgra.copyOf()) }
                if (out != null) break
            }
        }
        return out!!
    }

    private fun shaderFrame(n: Int, hardware: Boolean): Triple<Int, Int, ByteArray> {
        var i = 0
        var planes: YuvRenderer.Planes? = null
        FfmpegH264Decoder(hardware).use { d ->
            println("shader path backend=${d.backend}")
            d.yuvSink = FfmpegH264Decoder.YuvSink { f -> if (++i == n) planes = YuvRenderer.upload(f) }
            for (p in packets()) {
                d.decode(p) { _, _, _ -> error("YUV 형식인데 BGRA로 왔다") }
                if (planes != null) break
            }
        }
        val pl = planes!!
        val surface = Surface.makeRaster(ImageInfo(pl.width, pl.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
        YuvRenderer.draw(surface.canvas, pl, Rect.makeWH(pl.width.toFloat(), pl.height.toFloat()))
        val pixels = ByteArray(pl.width * pl.height * 4)
        surface.readPixels(org.jetbrains.skia.Bitmap().apply {
            allocPixels(ImageInfo(pl.width, pl.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
        }.also { bmp -> surface.readPixels(bmp, 0, 0); bmp.readPixels()!!.copyInto(pixels) }, 0, 0)
        pl.close()
        surface.close()
        return Triple(pl.width, pl.height, pixels)
    }

    private fun meanDiff(a: ByteArray, b: ByteArray, w: Int, h: Int): Double {
        var sum = 0L
        for (p in 0 until w * h) for (c in 0 until 3) sum += abs((a[p * 4 + c].toInt() and 0xFF) - (b[p * 4 + c].toInt() and 0xFF))
        return sum.toDouble() / (w * h * 3)
    }

    @Test
    fun softwareI420ShaderMatchesSwscale() {
        val (w, h, ref) = bgraFrame(20, hardware = false)
        val (sw, sh, got) = shaderFrame(20, hardware = false)
        assertEquals(w to h, sw to sh)
        val d = meanDiff(ref, got, w, h)
        println("I420 mean channel diff=$d")
        assertTrue(d < 3.0, "평균 채널 차이 $d")
    }

    @Test
    fun hardwareNv12ShaderMatchesSwscale() {
        // 하드웨어 디코더가 없으면(Linux CI) 소프트웨어 I420으로 같은 확인을 한다.
        val (w, h, ref) = bgraFrame(20, hardware = true)
        val (sw, sh, got) = shaderFrame(20, hardware = true)
        assertEquals(w to h, sw to sh)
        val d = meanDiff(ref, got, w, h)
        println("NV12 mean channel diff=$d")
        assertTrue(d < 3.0, "평균 채널 차이 $d")
    }
}
