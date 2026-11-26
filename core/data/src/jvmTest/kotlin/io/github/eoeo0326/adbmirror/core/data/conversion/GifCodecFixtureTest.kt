package io.github.eoeo0326.adbmirror.core.data.conversion

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** 실제 화면 캡처를 스크롤한 프레임(화면 전체가 바뀌는 가장 무거운 경우)에서 참조 구현과 출력이 같은지 본다. */
class GifCodecFixtureTest {
    private val fixtures = File(System.getProperty("fixtures.dir") ?: error("fixtures.dir 시스템 속성이 없습니다"))
    private val quantizer = ColorQuantizer
    private val lzw = GifLzw

    /** fixture 화면을 [width] 너비로 줄이고, 프레임마다 12줄씩 스크롤하며 파란 채널에 ±1 잡음을 얹는다. */
    private fun scrollingFrames(width: Int, count: Int): List<RgbaFrame> {
        val src = ImageIO.read(File(fixtures, "scrcpy-v4.1-h264-rotate.start.png"))
        val height = src.height * width / src.width
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, width, height, null)
            dispose()
        }
        val base = scaled.getRGB(0, 0, width, height, null, 0, width)
        val noise = Random(136)
        return (0 until count).map { k ->
            RgbaFrame(width, height, IntArray(width * height) { i ->
                val c = base[((i / width + 12 * k) % height) * width + i % width]
                val b = ((c and 0xFF) + noise.nextInt(-1, 2)).coerceIn(0, 255)
                (c and 0xFFFFFF00.toInt()) or b
            })
        }
    }

    @Test
    fun scrollingScreenMatchesReference() {
        for (width in listOf(480, 720)) {
            val frames = scrollingFrames(width, 6)
            for (dither in listOf(false, true)) frames.forEachIndexed { k, f ->
                val where = "너비 $width, 프레임 $k, dither=$dither"
                val expected = ReferenceColorQuantizer.quantize(f, dither = dither)
                val actual = quantizer.quantize(f, dither = dither)
                assertContentEquals(expected.palette, actual.palette, "팔레트: $where")
                assertContentEquals(expected.indices, actual.indices, "인덱스: $where")
                assertContentEquals(ReferenceGifLzw.encode(expected.indices), lzw.encode(actual.indices), "LZW: $where")
            }
        }
    }
}
