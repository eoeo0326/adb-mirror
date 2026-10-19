package io.github.eoeo0326.adbmirror.core.domain.model

import kotlinx.coroutines.flow.Flow

/** 미러링 시작 옵션. */
data class MirrorOptions(val maxSize: Int, val maxFps: Int, val control: Boolean)

/** 진행 중인 미러링 하나. 영상 패킷은 상태가 아니라 흐름이라 [events]와 따로 흘린다. */
interface MirrorSession {
    val serial: String
    val events: Flow<SessionEvent>
    val packets: Flow<EncodedPacket>

    /** control=false로 연 세션이면 아무것도 하지 않는다. 보기 전용 판단은 `SendTouchUseCase`가 한다. */
    suspend fun sendTouch(event: TouchEvent)

    /** 키를 한 번 눌렀다 뗀다. */
    suspend fun pressKey(key: DeviceKey)

    /** 기기 키맵으로 칠 수 있는 글자를 친다. 키맵에 없는 글자(한글 등)는 서버가 버린다. */
    suspend fun injectText(text: String)

    /** 기기 클립보드에 넣고 붙여넣기 키를 누른다. 기기 클립보드를 덮어쓴다. */
    suspend fun pasteText(text: String)

    /** key frame을 즉시 요청한다(녹화 시작 시). */
    suspend fun requestKeyFrame()

    suspend fun stop()
}

sealed interface SessionEvent {
    data class DeviceName(val name: String) : SessionEvent
    data class VideoSizeChanged(val size: VideoSize) : SessionEvent
    data class Ended(val error: String? = null) : SessionEvent
}
