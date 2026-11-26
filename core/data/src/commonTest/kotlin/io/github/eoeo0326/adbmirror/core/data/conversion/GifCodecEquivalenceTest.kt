package io.github.eoeo0326.adbmirror.core.data.conversion

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** [ColorQuantizer]·[GifLzw]가 참조 구현([ReferenceColorQuantizer]·[ReferenceGifLzw])과 바이트 단위로 같은 결과를 내는지 본다. */
class GifCodecEquivalenceTest {
    // 한 인스턴스로 모든 입력을 이어서 돌린다. 앞 입력의 작업 버퍼 상태가 남으면 뒤 입력에서 드러난다.
    private val quantizer = ColorQuantizer
    private val lzw = GifLzw

    private fun frame(w: Int, h: Int, argb: (Int) -> Int) = RgbaFrame(w, h, IntArray(w * h, argb))

    /** 알파까지 무작위. 양자화는 RGB만 봐야 한다. */
    private fun random(w: Int, h: Int, seed: Int): RgbaFrame {
        val r = Random(seed)
        return frame(w, h) { r.nextInt() }
    }

    /** 서로 다른 [count]가지 색. 앞 [count]픽셀에 한 번씩 나오고 나머지는 무작위로 고른다. */
    private fun fewColors(w: Int, h: Int, count: Int, seed: Int): RgbaFrame {
        val r = Random(seed)
        // 홀수를 곱하면 2^24 안에서 일대일이라 색이 겹치지 않는다.
        val colors = IntArray(count) { (0xFF shl 24) or ((it * 0x9E3779 + seed) and 0xFFFFFF) }
        return frame(w, h) { if (it < count) colors[it] else colors[r.nextInt(count)] }
    }

    /** 채널마다 [levels]단계 값만 쓴다. 범위가 같은 상자가 많아 동점 처리를 본다. */
    private fun levels(w: Int, h: Int, levels: Int, seed: Int): RgbaFrame {
        val r = Random(seed)
        val step = 255 / (levels - 1)
        return frame(w, h) {
            (0xFF shl 24) or ((r.nextInt(levels) * step) shl 16) or ((r.nextInt(levels) * step) shl 8) or (r.nextInt(levels) * step)
        }
    }

    /** 그라데이션에 파란 채널 ±1 잡음을 얹은, 화면 녹화와 비슷한 프레임. */
    private fun screenLike(w: Int, h: Int, seed: Int): RgbaFrame {
        val r = Random(seed)
        return frame(w, h) { i ->
            val x = i % w
            val y = i / w
            val b = ((x + y) * 255 / (w + h) + r.nextInt(-1, 2)).coerceIn(0, 255)
            (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or b
        }
    }

    private fun frames(): List<Pair<String, RgbaFrame>> = listOf(
        "1x1" to frame(1, 1) { 0xFF123456.toInt() },
        "단색" to frame(50, 40) { 0xFF808080.toInt() },
        "256색" to fewColors(64, 64, 256, 1),
        "257색" to fewColors(64, 64, 257, 2),
        "단계 색" to levels(96, 80, 8, 3),
        "화면" to screenLike(240, 200, 4),
        "무작위 64x64" to random(64, 64, 5),
        "무작위 300x300(stride 1)" to random(300, 300, 6),
        "무작위 400x400(stride 2)" to random(400, 400, 7),
    )

    private fun assertSameQuantize(label: String, f: RgbaFrame, maxColors: Int, dither: Boolean) {
        val expected = ReferenceColorQuantizer.quantize(f, maxColors, dither)
        val actual = quantizer.quantize(f, maxColors, dither)
        val where = "$label, maxColors=$maxColors, dither=$dither"
        assertContentEquals(expected.palette, actual.palette, "팔레트: $where")
        assertContentEquals(expected.indices, actual.indices, "인덱스: $where")
    }

    @Test
    fun quantizeMatchesReference() {
        for ((label, f) in frames()) for (dither in listOf(false, true)) {
            assertSameQuantize(label, f, 256, dither)
            assertSameQuantize(label, f, 16, dither)
        }
    }

    private fun lzwInputs(): Map<String, ByteArray> {
        val r = Random(11)
        return mapOf(
            "빈 입력" to ByteArray(0),
            "1바이트" to byteArrayOf(7),
            "2바이트" to byteArrayOf(-1, -1),
            "무작위 200k" to ByteArray(200_000) { r.nextInt(256).toByte() },
            "4가지 값 무작위 200k" to ByteArray(200_000) { r.nextInt(4).toByte() },
            "반복" to ByteArray(100_000) { (it / 1000 % 5).toByte() },
            "한 값" to ByteArray(70_000) { 3 },
            "화면 인덱스" to ReferenceColorQuantizer.quantize(screenLike(240, 200, 4)).indices,
        )
    }

    @Test
    fun lzwMatchesReference() {
        for ((label, input) in lzwInputs()) assertContentEquals(ReferenceGifLzw.encode(input), lzw.encode(input), label)
    }

    @Test
    fun lzwWithSmallerMinCodeSizeMatchesReference() {
        val r = Random(12)
        val input = ByteArray(50_000) { r.nextInt(16).toByte() }
        assertContentEquals(ReferenceGifLzw.encode(input, 4), lzw.encode(input, 4))
    }

    /** 큰 입력과 작은 입력을 번갈아 넣어도 앞 호출의 상태가 결과에 섞이지 않는다. */
    @Test
    fun reusedInstanceMatchesReferenceInAnyOrder() {
        val big = random(300, 300, 21)
        val small = fewColors(8, 8, 3, 22)
        val screen = screenLike(240, 200, 23)
        listOf(big, small, screen, small, big).forEachIndexed { i, f -> assertSameQuantize("순서 $i", f, 256, dither = i % 2 == 1) }

        val inputs = lzwInputs()
        for (label in listOf("무작위 200k", "빈 입력", "1바이트", "4가지 값 무작위 200k", "무작위 200k", "화면 인덱스")) {
            val input = inputs.getValue(label)
            assertContentEquals(ReferenceGifLzw.encode(input), lzw.encode(input), "순서: $label")
        }
    }
}
