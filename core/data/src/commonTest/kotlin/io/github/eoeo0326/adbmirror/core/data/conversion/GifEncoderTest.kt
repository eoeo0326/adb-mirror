package io.github.eoeo0326.adbmirror.core.data.conversion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GifEncoderTest {
    private fun solid(w: Int, h: Int, argb: Int) = RgbaFrame(w, h, IntArray(w * h) { argb })

    @Test
    fun writesHeaderLoopAndTrailer() {
        val gif = GifEncoder(4, 3, loopCount = 3).apply { addFrame(solid(4, 3, 0xFF112233.toInt()), 100) }.finish()
        assertEquals("GIF89a", gif.copyOfRange(0, 6).decodeToString())
        assertEquals(4, (gif[6].toInt() and 0xFF) or ((gif[7].toInt() and 0xFF) shl 8))
        assertEquals(3, (gif[8].toInt() and 0xFF) or ((gif[9].toInt() and 0xFF) shl 8))
        assertEquals("NETSCAPE2.0", gif.copyOfRange(16, 27).decodeToString())
        assertEquals(2, (gif[29].toInt() and 0xFF) or ((gif[30].toInt() and 0xFF) shl 8)) // 3번 재생 = 처음 + 2번 반복
        assertEquals(0x3B, gif.last().toInt())
    }

    @Test
    fun playOnceHasNoLoopExtensionAndInfiniteIsZero() {
        val once = GifEncoder(1, 1, loopCount = 1).apply { addFrame(solid(1, 1, 0xFF000000.toInt()), 100) }.finish()
        assertEquals(0x21, once[13].toInt() and 0xFF)
        assertEquals(0xF9, once[14].toInt() and 0xFF) // 곧바로 그래픽 제어 확장
        val forever = GifEncoder(1, 1, loopCount = 0).apply { addFrame(solid(1, 1, 0xFF000000.toInt()), 100) }.finish()
        assertEquals("NETSCAPE2.0", forever.copyOfRange(16, 27).decodeToString())
        assertEquals(0, (forever[29].toInt() and 0xFF) or ((forever[30].toInt() and 0xFF) shl 8))
    }

    @Test
    fun delayIsRoundedToCentisecondsWithMinimumTwo() {
        fun delayOf(ms: Int): Int {
            val gif = GifEncoder(1, 1).apply { addFrame(solid(1, 1, 0xFF000000.toInt()), ms) }.finish()
            val gce = (0 until gif.size - 1).first { gif[it] == 0x21.toByte() && gif[it + 1] == 0xF9.toByte() }
            return (gif[gce + 4].toInt() and 0xFF) or ((gif[gce + 5].toInt() and 0xFF) shl 8)
        }
        assertEquals(7, delayOf(66))
        assertEquals(2, delayOf(5))
        assertEquals(10, delayOf(100))
    }

    @Test
    fun paletteKeepsExactColorsWhenFew() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val frame = RgbaFrame(3, 1, colors)
        val q = ColorQuantizer.quantize(frame)
        assertEquals(colors.map { it and 0xFFFFFF }.toSet(), q.palette.toSet())
        assertTrue((0..2).all { q.palette[q.indices[it].toInt() and 0xFF] == colors[it] and 0xFFFFFF })
    }

    @Test
    fun manyColorsAreReducedTo256() {
        val frame = RgbaFrame(64, 64, IntArray(64 * 64) { (it * 97) and 0xFFFFFF or (0xFF shl 24) })
        assertEquals(256, ColorQuantizer.quantize(frame).palette.size)
        assertEquals(256, ColorQuantizer.quantize(frame, dither = true).palette.size)
    }

    @Test
    fun rejectsMismatchedFrameSize() {
        assertFailsWith<IllegalArgumentException> { GifEncoder(2, 2).addFrame(solid(1, 1, 0), 10) }
    }
}
