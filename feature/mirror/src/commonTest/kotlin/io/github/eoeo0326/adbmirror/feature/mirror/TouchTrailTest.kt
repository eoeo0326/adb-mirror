package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.compose.ui.geometry.Offset
import io.github.eoeo0326.adbmirror.feature.mirror.video.Ripple
import io.github.eoeo0326.adbmirror.feature.mirror.video.TouchTrail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTrailTest {
    @Test
    fun pressLeavesRippleThatExpires() {
        val t = TouchTrail().press(10f, 20f).release()
        assertEquals(listOf(Ripple(10f, 20f)), t.ripples)
        assertEquals(listOf(Ripple(10f, 20f, 100)), t.advance(100).ripples)
        assertTrue(t.advance(TouchTrail.RIPPLE_MS).isIdle)
    }

    @Test
    fun dragPathStaysWhilePressedThenFades() {
        var t = TouchTrail().press(0f, 0f).move(5f, 5f).move(10f, 10f)
        t = t.advance(1_000)
        assertEquals(listOf(Offset(0f, 0f), Offset(5f, 5f), Offset(10f, 10f)), t.path)

        t = t.release().advance(TouchTrail.PATH_FADE_MS - 1)
        assertEquals(3, t.path.size)
        assertTrue(t.advance(1).isIdle)
    }

    @Test
    fun moveWithoutPressIsIgnored() {
        assertTrue(TouchTrail().move(1f, 1f).release().isIdle)
    }

    @Test
    fun newPressStartsNewPathButKeepsRipples() {
        val t = TouchTrail().press(0f, 0f).move(1f, 1f).release().press(50f, 50f)
        assertEquals(listOf(Offset(50f, 50f)), t.path)
        assertEquals(2, t.ripples.size)
    }

    @Test
    fun pathIsCapped() {
        var t = TouchTrail().press(0f, 0f)
        repeat(TouchTrail.MAX_PATH_POINTS + 10) { t = t.move(it.toFloat(), 0f) }
        assertEquals(TouchTrail.MAX_PATH_POINTS, t.path.size)
    }
}
