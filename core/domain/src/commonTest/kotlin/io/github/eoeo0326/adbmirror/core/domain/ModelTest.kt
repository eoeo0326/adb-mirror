package io.github.eoeo0326.adbmirror.core.domain

import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun defaultConversionOptionsAreValid() {
        assertTrue(ConversionOptions().problems().isEmpty())
    }

    @Test
    fun conversionOptionsBoundaries() {
        assertTrue(ConversionOptions(fps = 5, width = 240, quality = 0).problems().isEmpty())
        assertTrue(ConversionOptions(fps = 30, width = 1080, quality = 100).problems().isEmpty())
        assertEquals(3, ConversionOptions(fps = 4, width = 1081, quality = 101).problems().size)
    }

    @Test
    fun videoSizeMustBePositive() {
        assertFailsWith<IllegalArgumentException> { VideoSize(0, 720) }
    }
}
