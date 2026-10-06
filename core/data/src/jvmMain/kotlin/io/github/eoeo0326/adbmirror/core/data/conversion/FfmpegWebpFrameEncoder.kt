package io.github.eoeo0326.adbmirror.core.data.conversion

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FFmpeg에 들어 있는 libwebp로 프레임 한 장을 WebP(RIFF 전체)로 만든다. 애니메이션 컨테이너는 [AnimatedWebpMuxer]가 묶는다.
 * 프레임마다 인코더를 새로 열어 상태를 남기지 않는다.
 */
class FfmpegWebpFrameEncoder private constructor(private val codec: AVCodec) : WebpFrameEncoder {
    override suspend fun encode(frame: RgbaFrame, quality: Int): ByteArray {
        val ctx = avcodec.avcodec_alloc_context3(codec) ?: error("WebP 인코더를 만들지 못했습니다")
        val av = avutil.av_frame_alloc()
        val packet = avcodec.av_packet_alloc()
        try {
            ctx.width(frame.width)
            ctx.height(frame.height)
            ctx.pix_fmt(avutil.AV_PIX_FMT_BGRA) // libwebp가 받는 RGB32(리틀엔디언 BGRA)
            ctx.time_base(avutil.av_make_q(1, 1000))
            val options = AVDictionary(null)
            avutil.av_dict_set(options, "quality", quality.coerceIn(0, 100).toString(), 0)
            avutil.av_dict_set(options, "lossless", "0", 0)
            val opened = avcodec.avcodec_open2(ctx, codec, options)
            avutil.av_dict_free(options)
            check(opened >= 0) { "WebP 인코더를 열지 못했습니다($opened)" }

            av.format(avutil.AV_PIX_FMT_BGRA)
            av.width(frame.width)
            av.height(frame.height)
            check(avutil.av_frame_get_buffer(av, 0) >= 0) { "WebP 입력 버퍼를 잡지 못했습니다" }
            val row = ByteArray(frame.width * 4)
            val ints = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val stride = av.linesize(0)
            val plane = av.data(0)
            for (y in 0 until frame.height) {
                ints.rewind()
                ints.put(frame.pixels, y * frame.width, frame.width)
                plane.position(y.toLong() * stride).put(row, 0, row.size)
            }
            plane.position(0)
            av.pts(0)

            check(avcodec.avcodec_send_frame(ctx, av) >= 0) { "WebP 인코딩에 실패했습니다" }
            check(avcodec.avcodec_receive_packet(ctx, packet) >= 0) { "WebP 결과를 받지 못했습니다" }
            return ByteArray(packet.size()).also { packet.data().get(it) }
        } finally {
            avcodec.av_packet_free(packet)
            avutil.av_frame_free(av)
            avcodec.avcodec_free_context(ctx)
        }
    }

    companion object {
        /** FFmpeg 빌드에 libwebp가 없으면 null(그러면 GIF만 만든다). */
        fun createOrNull(): FfmpegWebpFrameEncoder? =
            runCatching { avcodec.avcodec_find_encoder_by_name("libwebp") }.getOrNull()?.let(::FfmpegWebpFrameEncoder)
    }
}
