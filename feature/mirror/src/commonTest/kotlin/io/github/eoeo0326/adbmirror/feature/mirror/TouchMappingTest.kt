package io.github.eoeo0326.adbmirror.feature.mirror

import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import io.github.eoeo0326.adbmirror.feature.mirror.video.FitRect
import io.github.eoeo0326.adbmirror.feature.mirror.video.fitRect
import io.github.eoeo0326.adbmirror.feature.mirror.video.videoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TouchMappingTest {
    private val portrait = VideoSize(340, 720)

    @Test
    fun fitsWithSideLetterbox() {
        // 1000x720 뷰에 340x720 영상 → 좌우 여백 330px
        assertEquals(FitRect(330f, 0f, 340f, 720f), fitRect(1000f, 720f, portrait))
    }

    @Test
    fun mapsInsidePointsAndRejectsLetterbox() {
        assertEquals(0 to 0, videoPoint(330f, 0f, 1000f, 720f, portrait, clamp = false))
        assertEquals(170 to 360, videoPoint(500f, 360f, 1000f, 720f, portrait, clamp = false))
        assertNull(videoPoint(100f, 360f, 1000f, 720f, portrait, clamp = false))
    }

    @Test
    fun clampsWhileDragging() {
        assertEquals(0 to 360, videoPoint(100f, 360f, 1000f, 720f, portrait, clamp = true))
        assertEquals(339 to 719, videoPoint(990f, 900f, 1000f, 720f, portrait, clamp = true))
    }

    @Test
    fun scalesDownLargerVideo() {
        // 1080x2280 영상을 540x1140 뷰에 → 절반 크기
        val big = VideoSize(1080, 2280)
        assertEquals(540 to 1140, videoPoint(270f, 570f, 540f, 1140f, big, clamp = false))
    }
}
