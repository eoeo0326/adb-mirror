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

    @Test
    fun outputSizeKeepsAspectAndNeverUpscales() {
        val info = io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo(10_000, 606, 1280)
        assertEquals(480 to 1013, ConversionOptions(width = 480).outputSize(info))
        assertEquals(606 to 1280, ConversionOptions(width = 1080).outputSize(info))
    }

    @Test
    fun estimatedBytesGrowsWithFpsAndDuration() {
        val info = io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo(10_000, 606, 1280)
        val base = ConversionOptions(width = 480, fps = 10).estimatedBytes(info)
        assertEquals(base * 2, ConversionOptions(width = 480, fps = 20).estimatedBytes(info))
        assertEquals(base / 2, ConversionOptions(width = 480, fps = 10, endMs = 5_000).estimatedBytes(info))
        assertTrue(ConversionOptions(format = io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat.WebP, width = 480, fps = 10).estimatedBytes(info) < base)
    }
}
