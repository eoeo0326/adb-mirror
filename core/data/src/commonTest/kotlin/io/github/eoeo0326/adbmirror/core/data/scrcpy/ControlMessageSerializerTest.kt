package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.domain.model.DeviceKey
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

    @Test
    fun keyPressIsDownThenUpKeycode() {
        // type 0, action, keycode 66(Enter), repeat 0, metastate 0
        assertEquals(
            "00" + "00" + "00000042" + "00000000" + "00000000" +
                "00" + "01" + "00000042" + "00000000" + "00000000",
            hex(ControlMessageSerializer.keyPress(DeviceKey.Enter)),
        )
    }

    @Test
    fun injectTextHasLengthPrefixedUtf8() {
        assertEquals("01" + "00000002" + "6869", hex(ControlMessageSerializer.injectText("hi")))
    }

    @Test
    fun setClipboardWithPasteHasZeroSequence() {
        // "한" = ed 95 9c
        assertEquals(
            "09" + "0000000000000000" + "01" + "00000003" + "ed959c",
            hex(ControlMessageSerializer.setClipboard("한", paste = true)),
        )
    }

    @Test
    fun textOverLimitIsCutAtCharacterBoundary() {
        // 3바이트 글자 100개(300바이트) 뒤에 한 글자 더: 301바이트째가 글자 중간이 되지 않게 300바이트에서 자른다
        val bytes = ControlMessageSerializer.injectText("가".repeat(101))
        assertEquals(1 + 4 + ControlMessageSerializer.INJECT_TEXT_MAX_BYTES, bytes.size)
        val bytes2 = ControlMessageSerializer.injectText("a" + "가".repeat(100))
        assertEquals("0000012a", hex(bytes2.copyOfRange(1, 5))) // 1 + 3 * 99 = 298바이트
    }
}
