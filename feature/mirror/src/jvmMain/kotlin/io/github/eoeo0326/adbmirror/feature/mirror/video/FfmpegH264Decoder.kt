package io.github.eoeo0326.adbmirror.feature.mirror.video

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.DoublePointer

/**
 * FFmpeg(libavcodec) H.264 디코더. Annex B 패킷을 그대로 넣고 BGRA 프레임을 받는다.
 * 한 스레드에서만 쓴다. 지연을 줄이려고 프레임 스레딩 대신 low-delay·slice 스레딩을 쓴다.
 */
class FfmpegH264Decoder : AutoCloseable {
    /** [bgra]는 다음 [decode] 호출에서 덮어쓰인다. 필요하면 복사해 쓴다. */
    fun interface FrameSink {
        fun onFrame(width: Int, height: Int, bgra: ByteArray)
    }

    private val context: AVCodecContext
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val frame: AVFrame = avutil.av_frame_alloc()
    private var sws: SwsContext? = null
    private val bgraFrame: AVFrame = avutil.av_frame_alloc()
    private var dstBytes = ByteArray(0)

    init {
        val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264) ?: error("FFmpeg H.264 디코더가 없습니다")
        context = avcodec.avcodec_alloc_context3(codec)
        context.flags(context.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        context.thread_type(FF_THREAD_SLICE)
        context.thread_count(0)
        check(avcodec.avcodec_open2(context, codec, null as AVDictionary?) >= 0) { "H.264 디코더를 열지 못했습니다" }
    }

    /** config 패킷도 그대로 넣는다(SPS·PPS는 스트림 안에서 읽는다). 나온 프레임마다 [sink]를 부른다. */
    fun decode(data: ByteArray, sink: FrameSink) {
        if (avcodec.av_new_packet(packet, data.size) < 0) return
        packet.data().put(data, 0, data.size)
        val sent = avcodec.avcodec_send_packet(context, packet)
        avcodec.av_packet_unref(packet)
        if (sent < 0) return
        while (avcodec.avcodec_receive_frame(context, frame) == 0) {
            convert(sink)
        }
    }

    /** 회전 등으로 새 세션이 시작되면 디코더 안의 이전 프레임을 버린다. */
    fun flush() = avcodec.avcodec_flush_buffers(context)

    private fun convert(sink: FrameSink) {
        val w = frame.width()
        val h = frame.height()
        if (w <= 0 || h <= 0) return
        sws = swscale.sws_getCachedContext(
            sws, w, h, frame.format(), w, h, avutil.AV_PIX_FMT_BGRA,
            swscale.SWS_BILINEAR, null, null, null as DoublePointer?,
        )
        if (bgraFrame.width() != w || bgraFrame.height() != h) {
            // 해상도가 바뀌면(회전) 목적지 버퍼를 새로 잡는다.
            avutil.av_frame_unref(bgraFrame)
            bgraFrame.format(avutil.AV_PIX_FMT_BGRA)
            bgraFrame.width(w)
            bgraFrame.height(h)
            check(avutil.av_frame_get_buffer(bgraFrame, 0) >= 0) { "BGRA 버퍼를 잡지 못했습니다" }
            dstBytes = ByteArray(w * h * 4)
        }
        if (swscale.sws_scale_frame(sws, bgraFrame, frame) < 0) return
        // 줄 끝에 정렬 패딩이 있을 수 있어 한 줄씩 복사한다.
        val rowBytes = w * 4
        val stride = bgraFrame.linesize(0)
        val plane = bgraFrame.data(0)
        for (y in 0 until h) plane.position(y.toLong() * stride).get(dstBytes, y * rowBytes, rowBytes)
        plane.position(0)
        sink.onFrame(w, h, dstBytes)
    }

    private companion object {
        /** libavcodec/avcodec.h의 FF_THREAD_SLICE. bytedeco가 상수로 노출하지 않는다. */
        const val FF_THREAD_SLICE = 2
    }

    override fun close() {
        sws?.let(swscale::sws_freeContext)
        avutil.av_frame_free(bgraFrame)
        avutil.av_frame_free(frame)
        avcodec.av_packet_free(packet)
        avcodec.avcodec_free_context(context)
    }
}
