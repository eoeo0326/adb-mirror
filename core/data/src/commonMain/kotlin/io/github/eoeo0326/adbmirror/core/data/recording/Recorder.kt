package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/** 녹화 파일 한 조각(part)을 쓰는 곳. 파일 시스템은 플랫폼마다 다르다. */
interface RecordingPart {
    val path: String
    fun write(bytes: ByteArray)
    fun close()
}

fun interface RecordingOutput {
    /** [index]번째(1부터) 파일을 연다. */
    fun openPart(index: Int): RecordingPart
}

/**
 * 한 기기의 녹화. 패킷을 받아 part 파일에 fragmented MP4로 이어 쓴다. 한 스레드(코루틴)에서만 부른다.
 *
 * - 크기나 config가 바뀌면(회전) 지금 part를 닫고 다음 part를 시작한다. 파일은 그 뒤 첫 key frame에 연다.
 * - 크기·config가 같은 config(key frame 재요청)는 같은 part에 이어 쓴다.
 * - PTS가 앞으로 가지 않는 프레임은 버린다(MP4 샘플 길이가 음수가 되지 않게).
 */
class Recorder(private val output: RecordingOutput) {
    private var muxer: Mp4Muxer? = null
    private var config: ByteArray? = null
    private var size: VideoSize? = null
    private var part: RecordingPart? = null
    private var partCount = 0
    private var partFirstPtsUs = 0L
    private var lastPtsUs: Long? = null
    private var finishedDurationUs = 0L
    private val files = mutableListOf<String>()

    fun accept(packet: EncodedPacket) {
        when (packet.kind) {
            EncodedPacket.Kind.Config -> onConfig(packet)
            EncodedPacket.Kind.KeyFrame, EncodedPacket.Kind.Frame -> onFrame(packet)
        }
    }

    private fun onConfig(packet: EncodedPacket) {
        val newSize = packet.videoSize ?: return
        if (muxer != null && newSize == size && packet.data.contentEquals(config)) return
        closePart()
        muxer = Mp4Muxer(newSize, packet)
        config = packet.data
        size = newSize
    }

    private fun onFrame(packet: EncodedPacket) {
        val m = muxer ?: return
        val pts = packet.ptsUs ?: return
        if (lastPtsUs?.let { pts <= it } == true) return
        val p = part ?: run {
            if (packet.kind != EncodedPacket.Kind.KeyFrame) return
            output.openPart(++partCount).also {
                it.write(m.header())
                files += it.path
                part = it
                partFirstPtsUs = pts
            }
        }
        m.addFrame(packet)?.let(p::write)
        lastPtsUs = pts
    }

    private fun closePart() {
        val p = part
        val m = muxer
        if (p != null && m != null) {
            try {
                m.finish()?.let(p::write)
            } finally {
                p.close()
            }
            finishedDurationUs += (lastPtsUs ?: partFirstPtsUs) - partFirstPtsUs
        }
        part = null
        muxer = null
    }

    /** 남은 프레임을 쓰고 닫는다. 쓴 파일(없으면 빈 목록)과 길이(ms)를 돌려준다. */
    fun finish(): Result {
        closePart()
        return Result(files.toList(), finishedDurationUs / 1000)
    }

    data class Result(val files: List<String>, val durationMs: Long)
}
