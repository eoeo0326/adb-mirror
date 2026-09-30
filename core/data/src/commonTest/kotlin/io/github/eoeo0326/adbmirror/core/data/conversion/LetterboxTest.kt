package io.github.eoeo0326.adbmirror.core.data.conversion

import kotlin.test.Test
import kotlin.test.assertEquals

class LetterboxTest {
    @Test
    fun landscapeFitsInsidePortraitCanvas() {
        assertEquals(240 to 113, fitInside(720, 340, 240, 508))
        assertEquals(240 to 508, fitInside(340, 720, 240, 508))
        assertEquals(1 to 10, fitInside(1, 1000, 100, 10))
    }

    @Test
    fun letterboxCentersFrameOnBlack() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val out = letterbox(RgbaFrame(2, 1, intArrayOf(white, white)), 4, 3)
        assertEquals(
            listOf(black, black, black, black, black, white, white, black, black, black, black, black),
            out.pixels.toList(),
        )
    }
}
