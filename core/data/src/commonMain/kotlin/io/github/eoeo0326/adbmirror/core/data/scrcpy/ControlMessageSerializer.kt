package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent

/** scrcpy v4 컨트롤 메시지(클라이언트 → 기기) 직렬화. */
object ControlMessageSerializer {
    private const val TYPE_INJECT_TOUCH_EVENT: Byte = 2
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

    /** RESET_VIDEO. 인코더를 재시작해 곧바로 key frame을 받게 한다. */
    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO)

    /** MotionEvent.ACTION_* */
    private val TouchAction.androidCode: Int
        get() = when (this) {
            TouchAction.Down -> 0
            TouchAction.Up -> 1
            TouchAction.Move -> 2
        }
}
