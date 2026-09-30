package io.github.eoeo0326.adbmirror.core.data.conversion

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 만든 GIF를 JDK ImageIO(독립 디코더)로 읽어 프레임 수와 색을 확인한다. */
class GifImageIoTest {
    private fun gradient(w: Int, h: Int, phase: Int) = RgbaFrame(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val r = (x * 255 / (w - 1) + phase) and 0xFF
        val g = y * 255 / (h - 1)
        val b = (phase * 3) and 0xFF
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    })

    private fun channelDiff(a: Int, b: Int) = listOf(16, 8, 0).maxOf { abs(((a ushr it) and 0xFF) - ((b ushr it) and 0xFF)) }

    @Test
    fun imageIoDecodesAllFramesWithCloseColors() {
        val w = 160
        val h = 120
        val frames = (0 until 3).map { gradient(w, h, it * 40) }
        for (dither in listOf(false, true)) {
            val gif = GifEncoder(w, h, dither = dither).apply { frames.forEach { addFrame(it, 66) } }.finish()
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(gif))
            assertEquals(3, reader.getNumImages(true))
            frames.forEachIndexed { i, frame ->
                val img = reader.read(i)
                assertEquals(w, img.width)
                val diffs = (0 until w * h step 37).map { p -> channelDiff(img.getRGB(p % w, p / w), frame.pixels[p]) }
                assertTrue(diffs.average() < 12, "평균 채널 오차 ${diffs.average()} (dither=$dither)")
            }
            File("build/fixture-gif").mkdirs()
            File("build/fixture-gif/gradient${if (dither) "-dither" else ""}.gif").writeBytes(gif)
        }
    }
}
