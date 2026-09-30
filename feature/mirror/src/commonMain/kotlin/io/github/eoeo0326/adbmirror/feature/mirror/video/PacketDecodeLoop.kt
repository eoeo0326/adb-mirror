package io.github.eoeo0326.adbmirror.feature.mirror.video

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/** 캡처 세션 하나(해상도 하나)를 맡는 하드웨어 디코더. 한 스레드에서만 쓴다. */
interface PacketDecoder : AutoCloseable {
    val size: VideoSize

    /** 넣었으면 true. 입력 버퍼가 없어 버렸으면 false(다음 프레임이 깨질 수 있다). */
    fun decode(packet: EncodedPacket): Boolean
}

/**
 * 패킷 흐름을 디코더에 넣는 규칙(플랫폼 코덱과 무관한 부분).
 * - config에 실린 크기가 지금 디코더와 다르면(첫 config·회전) 디코더를 새로 만든다.
 * - config를 받기 전의 프레임은 그릴 수 없어 버린다.
 * - 프레임을 버렸으면 key frame을 다시 요청하되 [keyFrameIntervalMs]에 한 번만(몰아서 요청하면 서버가 캡처를 거듭 다시 시작한다).
 */
class PacketDecodeLoop(
    private val newDecoder: (VideoSize) -> PacketDecoder,
    private val requestKeyFrame: () -> Unit,
    private val nowMs: () -> Long,
    private val keyFrameIntervalMs: Long = 1_000,
) : AutoCloseable {
    private var decoder: PacketDecoder? = null
    private var lastRequestMs: Long? = null

    /** 디코딩을 시작할 때(화면이 붙을 때) 부른다. 앞선 config·key frame은 이미 지나갔을 수 있다. */
    fun start() = requestKeyFrameThrottled()

    /** 프레임(설정이 아닌 패킷)을 디코더에 넣었으면 true. */
    fun accept(packet: EncodedPacket): Boolean {
        if (packet.kind == EncodedPacket.Kind.Config) {
            val size = packet.videoSize
            if (size != null && decoder?.size != size) {
                decoder?.close()
                decoder = null
                decoder = newDecoder(size)
            }
        }
        val d = decoder ?: return false
        val fed = d.decode(packet)
        if (!fed) requestKeyFrameThrottled()
        return fed && packet.kind != EncodedPacket.Kind.Config
    }

    private fun requestKeyFrameThrottled() {
        val now = nowMs()
        val last = lastRequestMs
        if (last != null && now - last < keyFrameIntervalMs) return
        lastRequestMs = now
        requestKeyFrame()
    }

    override fun close() {
        decoder?.close()
        decoder = null
    }
}
