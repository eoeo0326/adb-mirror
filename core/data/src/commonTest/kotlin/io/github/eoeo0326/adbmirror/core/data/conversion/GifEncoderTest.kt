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

    /** GIF 블록을 훑어 (이미지 위치·크기, 지연 cs) 목록을 돌려준다. */
    private fun images(gif: ByteArray): List<Pair<List<Int>, Int>> {
        fun u16(i: Int) = (gif[i].toInt() and 0xFF) or ((gif[i + 1].toInt() and 0xFF) shl 8)
        fun skipSubBlocks(start: Int): Int {
            var i = start
            while (true) {
                val n = gif[i].toInt() and 0xFF
                i += 1
                if (n == 0) return i
                i += n
            }
        }
        val out = mutableListOf<Pair<List<Int>, Int>>()
        var i = 13 // 헤더 6 + 논리 화면 7, 전역 팔레트 없음
        var delay = -1
        while (gif[i].toInt() and 0xFF != 0x3B) {
            when (gif[i].toInt() and 0xFF) {
                0x21 -> {
                    if (gif[i + 1].toInt() and 0xFF == 0xF9) delay = u16(i + 4)
                    i = skipSubBlocks(i + 2)
                }
                0x2C -> {
                    out += listOf(u16(i + 1), u16(i + 3), u16(i + 5), u16(i + 7)) to delay
                    val packed = gif[i + 9].toInt() and 0xFF
                    i += 10
                    if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 7) + 1))
                    i = skipSubBlocks(i + 1) // LZW 최소 코드 크기 1바이트 뒤 데이터
                }
                else -> error("알 수 없는 블록 0x${(gif[i].toInt() and 0xFF).toString(16)} @ $i")
            }
        }
        return out
    }

    @Test
    fun laterFramesWriteOnlyChangedRectangle() {
        val base = solid(10, 8, 0xFF000000.toInt())
        val changed = RgbaFrame(10, 8, base.pixels.copyOf().also { px ->
            for (y in 2..4) for (x in 3..4) px[y * 10 + x] = 0xFFFF0000.toInt()
        })
        val gif = GifEncoder(10, 8).apply { addFrame(base, 100); addFrame(changed, 200) }.finish()
        assertEquals(listOf(listOf(0, 0, 10, 8) to 10, listOf(3, 2, 2, 3) to 20), images(gif))
    }

    @Test
    fun identicalFramesMergeIntoOneLongerFrame() {
        val a = solid(4, 4, 0xFF112233.toInt())
        val b = solid(4, 4, 0xFF445566.toInt())
        val gif = GifEncoder(4, 4).apply {
            addFrame(a, 100)
            addFrame(solid(4, 4, 0xFF112233.toInt()), 100) // 내용이 같은 다른 객체
            addFrame(b, 50)
        }.finish()
        assertEquals(listOf(listOf(0, 0, 4, 4) to 20, listOf(0, 0, 4, 4) to 5), images(gif))
    }

    @Test
    fun veryLongDelayIsSplitAcrossFrames() {
        val gif = GifEncoder(1, 1).apply { addFrame(solid(1, 1, 0xFF000000.toInt()), 700_000) }.finish()
        assertEquals(listOf(0xFFFF, 70_000 - 0xFFFF), images(gif).map { it.second })
    }

    @Test
    fun decodingNoiseWithinToleranceIsNotWritten() {
        val base = solid(4, 4, 0xFF808080.toInt())
        val noisy = solid(4, 4, 0xFF838083.toInt()) // 채널 차이 3 ≤ 6
        val gif = GifEncoder(4, 4).apply { addFrame(base, 100); addFrame(noisy, 100) }.finish()
        assertEquals(listOf(listOf(0, 0, 4, 4) to 20), images(gif))
    }

    @Test
    fun slowDriftIsRewrittenOnceItExceedsTolerance() {
        // 프레임마다 채널이 2씩 오른다. 보이는 화면(첫 프레임)과 비교하므로 차이가 6을 넘는 네 번째 프레임에서 다시 쓴다.
        val gif = GifEncoder(2, 2).apply {
            for (k in 0..4) addFrame(solid(2, 2, (0xFF shl 24) or ((0x80 + 2 * k) shl 16)), 100)
        }.finish()
        // 첫 프레임이 k=0..3을 합쳐 40cs, k=4(차이 8)가 새로 쓰인다
        assertEquals(listOf(listOf(0, 0, 2, 2) to 40, listOf(0, 0, 2, 2) to 10), images(gif))
    }

    @Test
    fun zeroToleranceWritesEveryChange() {
        val gif = GifEncoder(4, 4, tolerance = 0).apply {
            addFrame(solid(4, 4, 0xFF808080.toInt()), 100)
            addFrame(solid(4, 4, 0xFF818080.toInt()), 100)
        }.finish()
        assertEquals(2, images(gif).size)
    }
}
