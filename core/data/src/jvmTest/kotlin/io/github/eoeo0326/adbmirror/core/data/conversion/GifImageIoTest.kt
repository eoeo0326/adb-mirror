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

    /**
     * 바뀐 영역만 쓴 GIF를 ImageIO로 읽어 각 이미지를 제자리에 겹쳐 그리면 원래 프레임과 같다.
     * 색이 256개 이하라 양자화 오차 없이 그대로 나와야 한다.
     */
    @Test
    fun deltaFramesCompositeBackToSourceFrames() {
        val w = 64
        val h = 48
        val colors = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        val frames = (0 until 4).map { f ->
            RgbaFrame(w, h, IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                // 배경은 그대로, 움직이는 8×8 사각형만 바뀐다
                if (x in (f * 10) until (f * 10 + 8) && y in 10 until 18) colors[2 + f % 2] else colors[(x / 16 + y / 16) % 2]
            })
        }
        val gif = GifEncoder(w, h).apply { frames.forEach { addFrame(it, 100) } }.finish()
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(gif))
        assertEquals(4, reader.getNumImages(true))
        val canvas = IntArray(w * h)
        frames.forEachIndexed { i, frame ->
            val img = reader.read(i)
            val desc = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0").let { root ->
                (0 until root.childNodes.length).map { root.childNodes.item(it) }.first { it.nodeName == "ImageDescriptor" }
            }
            val left = desc.attributes.getNamedItem("imageLeftPosition").nodeValue.toInt()
            val top = desc.attributes.getNamedItem("imageTopPosition").nodeValue.toInt()
            if (i > 0) assertTrue(img.width < w || img.height < h, "둘째 프레임부터는 부분 이미지")
            for (y in 0 until img.height) for (x in 0 until img.width) canvas[(top + y) * w + left + x] = img.getRGB(x, y)
            assertTrue(canvas.contentEquals(frame.pixels), "프레임 $i 합성 결과가 원본과 다르다")
        }
    }
}
