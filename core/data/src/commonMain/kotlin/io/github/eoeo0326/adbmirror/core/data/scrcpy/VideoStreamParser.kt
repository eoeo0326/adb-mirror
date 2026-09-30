package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.adb.ByteSource
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/**
 * scrcpy v4 영상 소켓 파서. forward 터널의 dummy byte는 이미 읽은 뒤의 스트림을 받는다.
 *
 * 순서: device meta(64B, NUL 패딩 이름) → codec id(u32) → [세션 | 미디어] 패킷 반복.
 * 세션 패킷은 MSB가 1인 12바이트(flags u32 + width u32 + height u32)이고,
 * 미디어 패킷은 12바이트 헤더(pts·flags u64 + size u32) 뒤에 payload가 온다.
 */
class VideoStreamParser(private val source: ByteSource) {

    data class Header(val deviceName: String, val codecId: Long)

    sealed interface Item {
        data class Session(val size: VideoSize) : Item
        class Packet(val packet: EncodedPacket) : Item
    }

    /** 스트림 맨 앞에서 한 번 호출한다. */
    suspend fun readHeader(): Header {
        val meta = source.readFully(DEVICE_NAME_LENGTH)
        val nameEnd = meta.indexOf(0).let { if (it < 0) meta.size else it }
        val name = meta.copyOfRange(0, nameEnd).decodeToString()
        val codec = source.readFully(4).u32(0)
        return Header(name, codec)
    }

    /** 다음 세션 또는 미디어 패킷. 스트림이 끝나면 `EndOfStreamException`. */
    suspend fun readItem(): Item {
        val header = source.readFully(HEADER_LENGTH)
        val first = header.u64(0)
        if (first and FLAG_SESSION != 0UL) {
            val width = header.u32(4).toInt()
            val height = header.u32(8).toInt()
            return Item.Session(VideoSize(width, height))
        }
        val size = header.u32(8).toInt()
        val payload = source.readFully(size)
        val packet = if (first and FLAG_CONFIG != 0UL) {
            EncodedPacket(EncodedPacket.Kind.Config, ptsUs = null, data = payload)
        } else {
            val kind = if (first and FLAG_KEY_FRAME != 0UL) EncodedPacket.Kind.KeyFrame else EncodedPacket.Kind.Frame
            EncodedPacket(kind, ptsUs = (first and PTS_MASK).toLong(), data = payload)
        }
        return Item.Packet(packet)
    }

    companion object {
        const val DEVICE_NAME_LENGTH = 64
        const val HEADER_LENGTH = 12
        const val CODEC_H264: Long = 0x68323634 // "h264"

        private val FLAG_SESSION = 1UL shl 63
        private val FLAG_CONFIG = 1UL shl 62
        private val FLAG_KEY_FRAME = 1UL shl 61
        private val PTS_MASK = (1UL shl 61) - 1UL
    }
}
