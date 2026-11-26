package io.github.eoeo0326.adbmirror.core.data.conversion

import kotlin.random.Random
import kotlin.test.assertContentEquals

/** GIF 양자화·LZW 비교 테스트에 쓰는 프레임. */
internal object GifTestFrames {
    fun frame(w: Int, h: Int, argb: (Int) -> Int) = RgbaFrame(w, h, IntArray(w * h, argb))

    /** 알파까지 무작위. 양자화는 RGB만 봐야 한다. */
    fun random(w: Int, h: Int, seed: Int): RgbaFrame {
        val r = Random(seed)
        return frame(w, h) { r.nextInt() }
    }

    /** 서로 다른 [count]가지 색. 앞 [count]픽셀에 한 번씩 나오고 나머지는 무작위로 고른다. */
    fun fewColors(w: Int, h: Int, count: Int, seed: Int): RgbaFrame {
        val r = Random(seed)
        // 홀수를 곱하면 2^24 안에서 일대일이라 색이 겹치지 않는다.
        val colors = IntArray(count) { (0xFF shl 24) or ((it * 0x9E3779 + seed) and 0xFFFFFF) }
        return frame(w, h) { if (it < count) colors[it] else colors[r.nextInt(count)] }
    }

    /** 채널마다 [levels]단계 값만 쓴다. 범위가 같은 상자가 많아 동점 처리를 본다. */
    fun levels(w: Int, h: Int, levels: Int, seed: Int): RgbaFrame {
        val r = Random(seed)
        val step = 255 / (levels - 1)
        return frame(w, h) {
            (0xFF shl 24) or ((r.nextInt(levels) * step) shl 16) or ((r.nextInt(levels) * step) shl 8) or (r.nextInt(levels) * step)
        }
    }

    /** 그라데이션에 파란 채널 ±1 잡음을 얹은, 화면 녹화와 비슷한 프레임. */
    fun screenLike(w: Int, h: Int, seed: Int): RgbaFrame {
        val r = Random(seed)
        return frame(w, h) { i ->
            val x = i % w
            val y = i / w
            val b = ((x + y) * 255 / (w + h) + r.nextInt(-1, 2)).coerceIn(0, 255)
            (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or b
        }
    }
}

/** [quantizer]가 [ReferenceColorQuantizer]와 같은 팔레트·인덱스를 내는지 보고, 그 결과를 돌려준다. */
internal fun assertQuantizeMatchesReference(
    quantizer: ColorQuantizer,
    label: String,
    frame: RgbaFrame,
    maxColors: Int = 256,
    dither: Boolean = false,
): ColorQuantizer.Result {
    val expected = ReferenceColorQuantizer.quantize(frame, maxColors, dither)
    val actual = quantizer.quantize(frame, maxColors, dither)
    val where = "$label, maxColors=$maxColors, dither=$dither"
    assertContentEquals(expected.palette, actual.palette, "팔레트: $where")
    assertContentEquals(expected.indices, actual.indices, "인덱스: $where")
    return actual
}

/** [lzw]가 [ReferenceGifLzw]와 같은 바이트를 내는지 본다. */
internal fun assertLzwMatchesReference(lzw: GifLzw, label: String, input: ByteArray, minCodeSize: Int = 8) {
    assertContentEquals(ReferenceGifLzw.encode(input, minCodeSize), lzw.encode(input, minCodeSize), "LZW: $label")
}
