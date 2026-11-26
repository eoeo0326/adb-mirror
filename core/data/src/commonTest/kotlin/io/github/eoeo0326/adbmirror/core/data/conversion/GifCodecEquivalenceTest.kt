package io.github.eoeo0326.adbmirror.core.data.conversion

import io.github.eoeo0326.adbmirror.core.data.conversion.GifTestFrames.fewColors
import io.github.eoeo0326.adbmirror.core.data.conversion.GifTestFrames.frame
import io.github.eoeo0326.adbmirror.core.data.conversion.GifTestFrames.levels
import io.github.eoeo0326.adbmirror.core.data.conversion.GifTestFrames.random
import io.github.eoeo0326.adbmirror.core.data.conversion.GifTestFrames.screenLike
import kotlin.random.Random
import kotlin.test.Test

/**
 * [ColorQuantizer]·[GifLzw]가 참조 구현([ReferenceColorQuantizer]·[ReferenceGifLzw])과 바이트 단위로 같은 결과를 내는지 본다.
 *
 * wasmJs(브라우저) 테스트는 테스트 하나에 2초 제한이 있고 참조 구현은 느리다. 그래서 여기서는 프레임을 작게 두고 테스트를 나눈다.
 * 샘플이 65536을 넘거나 stride가 2 이상인 큰 프레임은 jvmTest의 `GifCodecLargeFrameTest`가 본다.
 */
class GifCodecEquivalenceTest {
    // 한 인스턴스로 테스트 안의 입력을 이어서 돌린다. 앞 입력의 작업 버퍼 상태가 남으면 뒤 입력에서 드러난다.
    private val quantizer = ColorQuantizer()
    private val lzw = GifLzw()

    private fun assertAllSettingsMatch(label: String, f: RgbaFrame) {
        for (dither in listOf(false, true)) for (maxColors in listOf(256, 16)) {
            assertQuantizeMatchesReference(quantizer, label, f, maxColors, dither)
        }
    }

    @Test
    fun quantizeTinyAndFewColorFramesMatchReference() {
        assertAllSettingsMatch("1x1", frame(1, 1) { 0xFF123456.toInt() })
        assertAllSettingsMatch("단색", frame(50, 40) { 0xFF808080.toInt() })
        assertAllSettingsMatch("256색", fewColors(64, 64, 256, 1))
        assertAllSettingsMatch("257색", fewColors(64, 64, 257, 2))
        assertAllSettingsMatch("단계 색", levels(96, 80, 8, 3))
    }

    @Test
    fun quantizeRandomFramesMatchReference() {
        assertAllSettingsMatch("무작위 64x64", random(64, 64, 5))
        assertAllSettingsMatch("무작위 128x128", random(128, 128, 6))
    }

    @Test
    fun quantizeScreenLikeFrameMatchesReference() {
        assertAllSettingsMatch("화면", screenLike(240, 200, 4))
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
        for ((label, input) in lzwInputs()) assertLzwMatchesReference(lzw, label, input)
    }

    @Test
    fun lzwWithSmallerMinCodeSizeMatchesReference() {
        val r = Random(12)
        assertLzwMatchesReference(lzw, "minCodeSize 4", ByteArray(50_000) { r.nextInt(16).toByte() }, minCodeSize = 4)
    }

    /** 큰 입력과 작은 입력을 번갈아 넣어도 앞 호출의 상태가 결과에 섞이지 않는다. */
    @Test
    fun reusedInstanceMatchesReferenceInAnyOrder() {
        val big = random(160, 160, 21)
        val small = fewColors(8, 8, 3, 22)
        val screen = screenLike(240, 200, 23) // big보다 커서 작업 버퍼가 다시 자란다
        listOf(big, small, screen, small, big).forEachIndexed { i, f ->
            assertQuantizeMatchesReference(quantizer, "순서 $i", f, dither = i % 2 == 1)
        }

        val inputs = lzwInputs()
        for (label in listOf("무작위 200k", "빈 입력", "1바이트", "4가지 값 무작위 200k", "무작위 200k", "화면 인덱스")) {
            assertLzwMatchesReference(lzw, "순서: $label", inputs.getValue(label))
        }
    }
}
