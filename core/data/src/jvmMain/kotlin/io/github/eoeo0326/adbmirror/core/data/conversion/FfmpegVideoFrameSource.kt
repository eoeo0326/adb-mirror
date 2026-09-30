package io.github.eoeo0326.adbmirror.core.data.conversion

import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.DoublePointer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FFmpeg(avformat·avcodec·swscale)으로 녹화 파일(MP4)을 프레임으로 푼다. 호출마다 파일을 새로 연다. */
class FfmpegVideoFrameSource : VideoFrameSource {
    override suspend fun info(file: String): VideoInfo = withContext(Dispatchers.IO) {
        openVideo(file) { fmt, stream, _ ->
            val par = stream.codecpar()
            VideoInfo(durationMs(fmt, stream), par.width(), par.height())
        }
    }

    override suspend fun decode(
        file: String,
        startMs: Long,
        width: Int,
        height: Int,
        onFrame: suspend (ptsMs: Long, frame: RgbaFrame) -> Boolean,
    ) {
        openVideo(file) { fmt, stream, decoder ->
            val tb = stream.time_base()
            val origin = stream.start_time().takeIf { it != avutil.AV_NOPTS_VALUE } ?: 0L
            if (startMs > 0) {
                // 시작 지점 앞의 key frame으로 간다. 실패하면 처음부터 읽는다.
                val ts = origin + startMs * tb.den() / (1000L * tb.num())
                avformat.av_seek_frame(fmt, stream.index(), ts, avformat.AVSEEK_FLAG_BACKWARD)
            }
            val packet = avcodec.av_packet_alloc()
            val frame = avutil.av_frame_alloc()
            val scaled = avutil.av_frame_alloc()
            var sws: SwsContext? = null
            val rowBytes = ByteArray(width * 4)
            try {
                scaled.format(avutil.AV_PIX_FMT_BGRA)
                scaled.width(width)
                scaled.height(height)
                check(avutil.av_frame_get_buffer(scaled, 0) >= 0) { "변환 버퍼를 잡지 못했습니다" }

                /** 디코더에서 나온 프레임을 모두 넘긴다. false면 그만 읽는다. */
                suspend fun drain(): Boolean {
                    while (avcodec.avcodec_receive_frame(decoder, frame) == 0) {
                        val pts = frame.best_effort_timestamp().takeIf { it != avutil.AV_NOPTS_VALUE } ?: continue
                        val ms = (pts - origin) * 1000L * tb.num() / tb.den()
                        sws = swscale.sws_getCachedContext(
                            sws, frame.width(), frame.height(), frame.format(), width, height, avutil.AV_PIX_FMT_BGRA,
                            swscale.SWS_BICUBIC, null, null, null as DoublePointer?,
                        )
                        if (swscale.sws_scale_frame(sws, scaled, frame) < 0) continue
                        if (!onFrame(ms, toArgb(scaled, width, height, rowBytes))) return false
                    }
                    return true
                }

                var keepGoing = true
                while (keepGoing && avformat.av_read_frame(fmt, packet) >= 0) {
                    if (packet.stream_index() == stream.index()) {
                        avcodec.avcodec_send_packet(decoder, packet)
                        keepGoing = drain()
                    }
                    avcodec.av_packet_unref(packet)
                }
                if (keepGoing) {
                    avcodec.avcodec_send_packet(decoder, null) // 디코더에 남은 프레임까지
                    drain()
                }
            } finally {
                sws?.let(swscale::sws_freeContext)
                avutil.av_frame_free(scaled)
                avutil.av_frame_free(frame)
                avcodec.av_packet_free(packet)
            }
        }
    }

    /** 줄 끝 정렬 패딩을 건너뛰며 BGRA(리틀엔디언 0xAARRGGBB)를 ARGB 정수로 읽는다. */
    private fun toArgb(scaled: AVFrame, width: Int, height: Int, row: ByteArray): RgbaFrame {
        val pixels = IntArray(width * height)
        val stride = scaled.linesize(0)
        val plane = scaled.data(0)
        val buffer = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        for (y in 0 until height) {
            plane.position(y.toLong() * stride).get(row, 0, row.size)
            buffer.rewind()
            buffer.get(pixels, y * width, width)
        }
        plane.position(0)
        return RgbaFrame(width, height, pixels)
    }

    private inline fun <R> openVideo(file: String, block: (AVFormatContext, AVStream, AVCodecContext) -> R): R {
        val fmt = AVFormatContext(null)
        check(avformat.avformat_open_input(fmt, file, null, null as AVDictionary?) >= 0) { "파일을 열지 못했습니다: $file" }
        var decoder: AVCodecContext? = null
        try {
            check(avformat.avformat_find_stream_info(fmt, null as AVDictionary?) >= 0) { "영상 정보를 읽지 못했습니다" }
            val index = avformat.av_find_best_stream(fmt, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, null as AVCodec?, 0)
            check(index >= 0) { "영상 트랙이 없습니다" }
            val stream = fmt.streams(index)
            val codec = avcodec.avcodec_find_decoder(stream.codecpar().codec_id()) ?: error("디코더가 없습니다")
            // 먼저 대입해 두어 열기에 실패해도 finally가 컨텍스트를 해제한다.
            val ctx = avcodec.avcodec_alloc_context3(codec).also { decoder = it }
            avcodec.avcodec_parameters_to_context(ctx, stream.codecpar())
            ctx.thread_count(0)
            check(avcodec.avcodec_open2(ctx, codec, null as AVDictionary?) >= 0) { "디코더를 열지 못했습니다" }
            return block(fmt, stream, ctx)
        } finally {
            decoder?.let { avcodec.avcodec_free_context(it) }
            avformat.avformat_close_input(fmt)
        }
    }

    /**
     * 길이(ms). fragmented MP4는 moov에 길이가 없어 컨테이너 값이 비어 있을 수 있다.
     * 그러면 패킷 헤더만 끝까지 읽어(디코딩 없이) 마지막 PTS + 길이로 잰다.
     */
    private fun durationMs(fmt: AVFormatContext, stream: AVStream): Long {
        val tb = stream.time_base()
        if (stream.duration() > 0) return stream.duration() * 1000L * tb.num() / tb.den()
        if (fmt.duration() > 0) return fmt.duration() / 1000
        val origin = stream.start_time().takeIf { it != avutil.AV_NOPTS_VALUE } ?: 0L
        val packet = avcodec.av_packet_alloc()
        var end = 0L
        try {
            while (avformat.av_read_frame(fmt, packet) >= 0) {
                if (packet.stream_index() == stream.index() && packet.pts() != avutil.AV_NOPTS_VALUE) {
                    end = maxOf(end, packet.pts() + packet.duration() - origin)
                }
                avcodec.av_packet_unref(packet)
            }
        } finally {
            avcodec.av_packet_free(packet)
        }
        return end * 1000L * tb.num() / tb.den()
    }
}
