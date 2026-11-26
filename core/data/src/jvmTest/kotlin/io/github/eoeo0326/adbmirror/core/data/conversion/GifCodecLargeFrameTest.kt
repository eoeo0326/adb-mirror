package io.github.eoeo0326.adbmirror.core.data.conversion

import io.github.eoeo0326.adbmirror.core.data.conversion.GifTestFrames.fewColors
import io.github.eoeo0326.adbmirror.core.data.conversion.GifTestFrames.random
import kotlin.test.Test

/**
 * 큰 프레임에서 [ColorQuantizer]가 참조 구현과 같은지 본다. 참조 구현이 느려 wasmJs의 테스트 시간 제한(2초)을 넘으므로 JVM에서만 돌린다.
 * 샘플 수는 `(픽셀 수 + stride - 1) / stride`이고 stride는 `max(1, 픽셀 수 / 65536)`이다.
 */
class GifCodecLargeFrameTest {
    private val quantizer = ColorQuantizer()

    private val stride1 = random(300, 300, 6) // 샘플 90000: 65536을 넘는다
    private val stride1Largest = random(359, 365, 8) // 샘플 131035: stride 1에서 가장 큰 쪽
    private val stride2 = random(400, 400, 7) // 샘플 80000

    @Test
    fun largeFramesMatchReference() {
        for ((label, f) in listOf("300x300(stride 1)" to stride1, "359x365(stride 1)" to stride1Largest, "400x400(stride 2)" to stride2)) {
            for (dither in listOf(false, true)) for (maxColors in listOf(256, 16)) {
                assertQuantizeMatchesReference(quantizer, label, f, maxColors, dither)
            }
        }
    }

    /** 큰 프레임과 작은 프레임을 번갈아 넣어도 작업 버퍼 상태가 결과에 섞이지 않는다. */
    @Test
    fun reusedInstanceMatchesReferenceAcrossLargeFrames() {
        val small = fewColors(8, 8, 3, 22)
        listOf(stride2, small, stride1, small, stride1Largest, stride2).forEachIndexed { i, f ->
            assertQuantizeMatchesReference(quantizer, "순서 $i", f, dither = i % 2 == 1)
        }
    }
}
