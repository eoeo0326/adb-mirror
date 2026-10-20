package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.domain.model.DeviceKey
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent

/** scrcpy v4 컨트롤 메시지(클라이언트 → 기기) 직렬화. */
object ControlMessageSerializer {
    private const val TYPE_INJECT_KEYCODE: Byte = 0
    private const val TYPE_INJECT_TEXT: Byte = 1
    private const val TYPE_INJECT_TOUCH_EVENT: Byte = 2
    private const val TYPE_SET_CLIPBOARD: Byte = 9
    private const val TYPE_RESET_VIDEO: Byte = 17

    /** POINTER_ID_GENERIC_FINGER. 서버가 마우스가 아닌 손가락 터치로 주입한다. */
    private const val POINTER_ID_GENERIC_FINGER = -2L

    /** INJECT_TOUCH_EVENT, 32바이트. 좌표·화면 크기는 현재 영상 기준이어야 서버가 받아들인다. */
    fun touch(event: TouchEvent): ByteArray {
        val out = ByteArray(32)
        var p = 0
        fun u8(v: Int) { out[p++] = v.toByte() }
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v) }
        fun u64(v: Long) { u32((v ushr 32).toInt()); u32(v.toInt()) }

        u8(TYPE_INJECT_TOUCH_EVENT.toInt())
        u8(event.action.androidCode)
        u64(POINTER_ID_GENERIC_FINGER)
        u32(event.x)
        u32(event.y)
        u16(event.videoSize.width)
        u16(event.videoSize.height)
        u16(if (event.action == TouchAction.Up) 0 else 0xFFFF) // pressure, u16 고정소수점(0xFFFF = 1.0)
        u32(0) // action button
        u32(0) // buttons
        check(p == out.size)
        return out
    }

    /** INJECT_KEYCODE 누름·뗌 한 쌍(14바이트씩). repeat·metastate는 0. */
    fun keyPress(key: DeviceKey): ByteArray = keycode(KEY_ACTION_DOWN, key.androidKeycode) + keycode(KEY_ACTION_UP, key.androidKeycode)

    private fun keycode(action: Int, keycode: Int): ByteArray {
        val out = Writer(14)
        out.u8(TYPE_INJECT_KEYCODE.toInt())
        out.u8(action)
        out.u32(keycode)
        out.u32(0) // repeat
        out.u32(0) // metastate
        return out.finish()
    }

    /** INJECT_TEXT. u32 길이 + UTF-8, 최대 [INJECT_TEXT_MAX_BYTES]바이트(넘으면 글자 경계에서 자른다). */
    fun injectText(text: String): ByteArray {
        val utf8 = text.encodeToByteArray().truncateUtf8(INJECT_TEXT_MAX_BYTES)
        val out = Writer(1 + 4 + utf8.size)
        out.u8(TYPE_INJECT_TEXT.toInt())
        out.u32(utf8.size)
        out.bytes(utf8)
        return out.finish()
    }

    /**
     * SET_CLIPBOARD. u64 sequence + u8 paste + u32 길이 + UTF-8, 최대 [CLIPBOARD_TEXT_MAX_BYTES]바이트.
     * sequence 0은 응답(ACK_CLIPBOARD)을 받지 않는다는 뜻이다. 컨트롤 소켓의 기기 메시지는 읽지 않으므로 0만 쓴다.
     */
    fun setClipboard(text: String, paste: Boolean): ByteArray {
        val utf8 = text.encodeToByteArray().truncateUtf8(CLIPBOARD_TEXT_MAX_BYTES)
        val out = Writer(1 + 8 + 1 + 4 + utf8.size)
        out.u8(TYPE_SET_CLIPBOARD.toInt())
        out.u64(0) // sequence
        out.u8(if (paste) 1 else 0)
        out.u32(utf8.size)
        out.bytes(utf8)
        return out.finish()
    }

    /** RESET_VIDEO. 인코더를 재시작해 곧바로 key frame을 받게 한다. */
    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO)

    /** scrcpy `SC_CONTROL_MSG_INJECT_TEXT_MAX_LENGTH` */
    const val INJECT_TEXT_MAX_BYTES = 300

    /** 메시지 최대 256KiB에서 type·sequence·paste·길이 14바이트를 뺀 값. scrcpy `SC_CONTROL_MSG_CLIPBOARD_TEXT_MAX_LENGTH` */
    const val CLIPBOARD_TEXT_MAX_BYTES = (1 shl 18) - 14

    private const val KEY_ACTION_DOWN = 0
    private const val KEY_ACTION_UP = 1

    /** [max]바이트를 넘으면 UTF-8 글자 중간이 아닌 곳에서 자른다. */
    internal fun ByteArray.truncateUtf8(max: Int): ByteArray {
        if (size <= max) return this
        var end = max
        while (end > 0 && (this[end].toInt() and 0xC0) == 0x80) end-- // 이어지는 바이트(10xxxxxx)면 글자 시작까지 물러난다
        return copyOf(end)
    }

    private class Writer(size: Int) {
        private val out = ByteArray(size)
        private var p = 0
        fun u8(v: Int) { out[p++] = v.toByte() }
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v) }
        fun u64(v: Long) { u32((v ushr 32).toInt()); u32(v.toInt()) }
        fun bytes(b: ByteArray) { b.copyInto(out, p); p += b.size }
        fun finish(): ByteArray = out.also { check(p == it.size) }
    }

    /** android.view.KeyEvent.KEYCODE_* */
    private val DeviceKey.androidKeycode: Int
        get() = when (this) {
            DeviceKey.Enter -> 66
            DeviceKey.Backspace -> 67 // KEYCODE_DEL
            DeviceKey.ForwardDelete -> 112
            DeviceKey.Tab -> 61
            DeviceKey.Escape -> 111
            DeviceKey.Up -> 19
            DeviceKey.Down -> 20
            DeviceKey.Left -> 21
            DeviceKey.Right -> 22
            DeviceKey.Home -> 122 // KEYCODE_MOVE_HOME
            DeviceKey.End -> 123 // KEYCODE_MOVE_END
            DeviceKey.PageUp -> 92
            DeviceKey.PageDown -> 93
        }

    /** MotionEvent.ACTION_* */
    private val TouchAction.androidCode: Int
        get() = when (this) {
            TouchAction.Down -> 0
            TouchAction.Up -> 1
            TouchAction.Move -> 2
        }
}
