package io.github.eoeo0326.adbmirror.feature.mirror.video

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVBufferRef
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
 *
 * [hardware]가 true면 OS 하드웨어 디코더(macOS VideoToolbox, Windows D3D11VA·DXVA2, Linux VAAPI)를 먼저 붙이고,
 * 없거나 열리지 않으면 소프트웨어로 디코딩한다. 하드웨어 프레임은 시스템 메모리로 옮긴 뒤 같은 변환을 거친다.
 */
class FfmpegH264Decoder(hardware: Boolean = hardwareDecodeEnabled()) : FrameDecoder {
    /** [bgra]는 다음 [decode] 호출에서 덮어쓰인다. 필요하면 복사해 쓴다. */
    fun interface FrameSink {
        fun onFrame(width: Int, height: Int, bgra: ByteArray)
    }

    private val context: AVCodecContext
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val frame: AVFrame = avutil.av_frame_alloc()
    private var sws: SwsContext? = null
    private val bgraFrame: AVFrame = avutil.av_frame_alloc()
    private val swFrame: AVFrame = avutil.av_frame_alloc()
    private var dstBytes = ByteArray(0)

    /** 하드웨어 디코더가 내는 픽셀 형식. 소프트웨어면 -1. */
    private var hwPixFmt = -1

    /** 실제로 쓰는 디코더 이름(`videotoolbox`, `software` 등). 측정 로그에 남긴다. */
    var backend: String = "software"
        private set

    init {
        val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264) ?: error("FFmpeg H.264 디코더가 없습니다")
        context = avcodec.avcodec_alloc_context3(codec)
        context.flags(context.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        context.thread_type(FF_THREAD_SLICE)
        context.thread_count(0)
        if (hardware) attachHardware(codec)
        check(avcodec.avcodec_open2(context, codec, null as AVDictionary?) >= 0) { "H.264 디코더를 열지 못했습니다" }
    }

    /**
     * 이 OS의 하드웨어 장치를 순서대로 열어 본다. 붙이면 FFmpeg 기본 get_format이 하드웨어 형식을 고른다.
     * 모두 실패하면 아무것도 하지 않는다(소프트웨어).
     */
    private fun attachHardware(codec: AVCodec) {
        for (name in hardwareCandidates()) {
            val type = avutil.av_hwdevice_find_type_by_name(name)
            if (type == avutil.AV_HWDEVICE_TYPE_NONE) continue
            val pixFmt = generateSequence(0) { it + 1 }
                .map { avcodec.avcodec_get_hw_config(codec, it) }
                .takeWhile { it != null }
                .firstOrNull { it!!.device_type() == type && (it.methods() and avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0 }
                ?.pix_fmt() ?: continue
            val device = AVBufferRef(null)
            if (avutil.av_hwdevice_ctx_create(device, type, null as String?, null as AVDictionary?, 0) < 0) continue
            context.hw_device_ctx(avutil.av_buffer_ref(device))
            avutil.av_buffer_unref(device)
            hwPixFmt = pixFmt
            backend = name
            return
        }
    }

    /** config 패킷도 그대로 넣는다(SPS·PPS는 스트림 안에서 읽는다). 나온 프레임마다 [sink]를 부른다. */
    override fun decode(data: ByteArray, sink: FrameSink) {
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
        // 하드웨어 프레임(GPU 메모리)은 시스템 메모리(NV12 등)로 옮겨 온다. 옮기지 못하면 이 프레임은 건너뛴다.
        val src = if (hwPixFmt >= 0 && frame.format() == hwPixFmt) {
            avutil.av_frame_unref(swFrame)
            if (avutil.av_hwframe_transfer_data(swFrame, frame, 0) < 0) return
            swFrame
        } else {
            frame
        }
        val w = src.width()
        val h = src.height()
        if (w <= 0 || h <= 0) return
        // swscale의 SIMD(NEON·SSE) 색 변환은 너비가 16의 배수일 때만 쓰인다(세로 폰은 606처럼 아닌 경우가 많다).
        // 디코더 버퍼는 줄 끝이 정렬돼 있으므로, 넉넉하면 변환 너비만 16의 배수로 올리고 복사할 때 원래 너비만 가져온다.
        val cw = alignedWidth(src, w)
        sws = swscale.sws_getCachedContext(
            sws, cw, h, src.format(), cw, h, avutil.AV_PIX_FMT_BGRA,
            swscale.SWS_BILINEAR, null, null, null as DoublePointer?,
        )
        if (bgraFrame.width() != cw || bgraFrame.height() != h) {
            // 해상도가 바뀌면(회전) 목적지 버퍼를 새로 잡는다.
            avutil.av_frame_unref(bgraFrame)
            bgraFrame.format(avutil.AV_PIX_FMT_BGRA)
            bgraFrame.width(cw)
            bgraFrame.height(h)
            check(avutil.av_frame_get_buffer(bgraFrame, 0) >= 0) { "BGRA 버퍼를 잡지 못했습니다" }
        }
        if (dstBytes.size != w * h * 4) dstBytes = ByteArray(w * h * 4)
        src.width(cw)
        val scaled = swscale.sws_scale_frame(sws, bgraFrame, src)
        src.width(w)
        if (scaled < 0) return
        // 줄 끝 정렬 패딩과 늘린 너비를 빼고 원래 너비만 복사한다.
        val rowBytes = w * 4
        val stride = bgraFrame.linesize(0)
        val plane = bgraFrame.data(0)
        if (stride == rowBytes) {
            plane.position(0).get(dstBytes, 0, rowBytes * h)
        } else {
            for (y in 0 until h) plane.position(y.toLong() * stride).get(dstBytes, y * rowBytes, rowBytes)
        }
        plane.position(0)
        sink.onFrame(w, h, dstBytes)
    }

    /** 16의 배수로 올린 너비. 원본 프레임의 어느 평면이든 줄 길이가 모자라면 그대로 [w]. */
    private fun alignedWidth(src: AVFrame, w: Int): Int {
        val aligned = (w + 15) and 15.inv()
        if (aligned == w) return w
        for (p in 0 until 4) {
            val need = avutil.av_image_get_linesize(src.format(), aligned, p)
            if (need <= 0) break
            if (src.linesize(p) < need) return w
        }
        return aligned
    }

    companion object {
        /** libavcodec/avcodec.h의 FF_THREAD_SLICE. bytedeco가 상수로 노출하지 않는다. */
        private const val FF_THREAD_SLICE = 2

        /** `ADB_MIRROR_HWDECODE=0`이면 하드웨어 디코더를 쓰지 않는다(비교 측정·문제 확인용). */
        fun hardwareDecodeEnabled(): Boolean = System.getenv("ADB_MIRROR_HWDECODE") != "0"

        private fun hardwareCandidates(): List<String> {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.startsWith("mac") -> listOf("videotoolbox")
                os.startsWith("windows") -> listOf("d3d11va", "dxva2")
                else -> listOf("vaapi")
            }
        }
    }

    override fun close() {
        sws?.let(swscale::sws_freeContext)
        avutil.av_frame_free(bgraFrame)
        avutil.av_frame_free(swFrame)
        avutil.av_frame_free(frame)
        avcodec.av_packet_free(packet)
        avcodec.avcodec_free_context(context)
    }
}
