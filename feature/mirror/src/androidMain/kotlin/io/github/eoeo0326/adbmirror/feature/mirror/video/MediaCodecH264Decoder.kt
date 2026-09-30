package io.github.eoeo0326.adbmirror.feature.mirror.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/**
 * Android 하드웨어 H.264 디코더(MediaCodec, 동기 모드). 디코딩한 프레임을 [surface]에 바로 그린다.
 * 한 캡처 세션(해상도 하나) 동안 쓰고, 해상도가 바뀌면 새로 만든다. 한 스레드에서만 쓴다.
 */
class MediaCodecH264Decoder(surface: Surface, override val size: VideoSize) : PacketDecoder {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MIME)
    private val info = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createVideoFormat(MIME, size.width, size.height)
        // 버퍼를 모았다가 내보내지 않게 해 지연을 줄인다(Android 11+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        try {
            codec.configure(format, surface, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /**
     * 패킷 하나를 넣고 나온 프레임을 그린다. 입력 버퍼가 빌 때까지 출력을 비우며 기다리고(최대 [INPUT_WAIT_MS]),
     * 그래도 없으면 버리고 false(그다음 P 프레임은 깨질 수 있어 부르는 쪽이 key frame을 다시 요청한다).
     */
    override fun decode(packet: EncodedPacket): Boolean {
        val deadline = System.nanoTime() + INPUT_WAIT_MS * 1_000_000
        var index = codec.dequeueInputBuffer(INPUT_POLL_US)
        while (index < 0) {
            drain()
            if (System.nanoTime() > deadline) return false
            index = codec.dequeueInputBuffer(INPUT_POLL_US)
        }
        val buffer = codec.getInputBuffer(index) ?: return false
        buffer.clear()
        buffer.put(packet.data)
        val flags = when (packet.kind) {
            EncodedPacket.Kind.Config -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            EncodedPacket.Kind.KeyFrame -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            EncodedPacket.Kind.Frame -> 0
        }
        codec.queueInputBuffer(index, 0, packet.data.size, packet.ptsUs ?: 0L, flags)
        drain()
        return true
    }

    /** 나온 프레임을 모두 곧바로 그린다(쌓아 두지 않음). */
    private fun drain() {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> codec.releaseOutputBuffer(out, true)
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> Unit // 출력 형식·버퍼 바뀜: 다시 읽는다
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val INPUT_POLL_US = 10_000L
        const val INPUT_WAIT_MS = 500L
    }
}
