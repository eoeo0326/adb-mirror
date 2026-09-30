package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlMessageSerializerTest {
    private val size = VideoSize(340, 720)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun touchDownMatchesProtocolLayout() {
        val bytes = ControlMessageSerializer.touch(TouchEvent(TouchAction.Down, 100, 200, size))
        assertEquals(32, bytes.size)
        assertEquals(
            "02" + "00" + "fffffffffffffffe" + "00000064" + "000000c8" + "0154" + "02d0" + "ffff" + "00000000" + "00000000",
            hex(bytes),
        )
    }

    @Test
    fun touchUpHasZeroPressureAndActionCode() {
        val bytes = ControlMessageSerializer.touch(TouchEvent(TouchAction.Up, 0, 0, size))
        assertEquals(1, bytes[1].toInt())
        assertEquals("0000", hex(bytes.copyOfRange(22, 24)))
    }

    @Test
    fun touchMoveActionCode() {
        val bytes = ControlMessageSerializer.touch(TouchEvent(TouchAction.Move, 339, 719, size))
        assertEquals(2, bytes[1].toInt())
        assertEquals("00000153" + "000002cf", hex(bytes.copyOfRange(10, 18)))
    }

    @Test
    fun resetVideoIsSingleTypeByte() {
        assertEquals("11", hex(ControlMessageSerializer.resetVideo()))
    }
}
