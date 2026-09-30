package io.github.eoeo0326.adbmirror.core.data.conversion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameSamplerTest {
    private fun run(sampler: FrameSampler<String>, frames: List<Pair<Long, String>>): List<Pair<String, Int>> =
        frames.flatMap { (pts, f) -> sampler.offer(pts, f) } + sampler.finish()

    @Test
    fun steadyFramesAreResampledToTargetFps() {
        // 원본 20fps(50ms) 1초 → 10fps: 한 장 건너 한 장
        val src = (0 until 20).map { it * 50L to "f$it" }
        val out = run(FrameSampler(0, 1000, 10), src)
        assertEquals((0 until 10).map { "f${it * 2}" to 100 }, out)
    }

    @Test
    fun staticScreenHoldsLastFrameAsOneLongFrame() {
        // 화면이 멈춰 0ms 다음 900ms에야 새 프레임
        val out = run(FrameSampler(0, 1000, 10), listOf(0L to "a", 900L to "b"))
        assertEquals(listOf("a" to 900, "b" to 100), out)
    }

    @Test
    fun startUsesLatestFrameBeforeIt() {
        val out = run(FrameSampler(500, 1000, 10), listOf(0L to "a", 400L to "b", 700L to "c"))
        assertEquals(listOf("b" to 200, "c" to 300), out)
    }

    @Test
    fun framesAfterEndAreIgnoredAndMarkDone() {
        // 출력 시각 0·100·200ms. 250ms의 b는 어느 출력 시각에도 걸리지 않는다.
        val sampler = FrameSampler<String>(0, 300, 10)
        val out = sampler.offer(0, "a") + sampler.offer(250, "b") + sampler.offer(400, "c")
        assertTrue(sampler.done)
        assertEquals(listOf("a" to 300), out + sampler.finish())
    }

    @Test
    fun lastFrameIsHeldUntilEnd() {
        // 5fps: 0ms에는 a, 200ms부터 끝(1000ms)까지 b
        val out = run(FrameSampler(0, 1000, 5), listOf(0L to "a", 100L to "b"))
        assertEquals(listOf("a" to 200, "b" to 800), out)
    }

    @Test
    fun progressReachesOneAtEnd() {
        val sampler = FrameSampler<String>(0, 1000, 10)
        sampler.offer(0, "a")
        sampler.offer(500, "b")
        assertEquals(0.5f, sampler.progress)
        sampler.finish()
        assertEquals(1f, sampler.progress)
    }
}
