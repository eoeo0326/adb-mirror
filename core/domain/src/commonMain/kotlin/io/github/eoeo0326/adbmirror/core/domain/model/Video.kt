package io.github.eoeo0326.adbmirror.core.domain.model

/** 현재 캡처 세션의 영상 크기(px). 회전하면 새 값이 온다. */
data class VideoSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "영상 크기는 양수여야 한다: ${width}x$height" }
    }
}

/** 인코더가 낸 H.264 패킷 하나(Annex B). */
class EncodedPacket(
    val kind: Kind,
    /** 프레임 PTS(µs). config 패킷은 PTS가 없어 null. */
    val ptsUs: Long?,
    val data: ByteArray,
) {
    enum class Kind { Config, KeyFrame, Frame }
}
