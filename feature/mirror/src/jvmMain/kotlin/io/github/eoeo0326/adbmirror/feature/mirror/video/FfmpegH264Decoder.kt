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
class FfmpegH264Decoder internal constructor(
    hardware: Boolean,
    /** 디코더 열기. 테스트가 하드웨어 열기 실패를 흉내 낼 때만 바꾼다. */
    private val openCodec: (AVCodecContext, AVCodec) -> Int,
) : FrameDecoder {
    constructor(hardware: Boolean = hardwareDecodeEnabled()) : this(hardware, { ctx, codec -> avcodec.avcodec_open2(ctx, codec, null as AVDictionary?) })

    /** [bgra]는 다음 [decode] 호출에서 덮어쓰인다. 필요하면 복사해 쓴다. */
    fun interface FrameSink {
        fun onFrame(width: Int, height: Int, bgra: ByteArray)
    }

    /** 색 변환 없이 받은 YUV 평면. 배열은 다음 [decode] 호출에서 덮어쓰인다. */
    fun interface YuvSink {
        fun onFrame(frame: YuvFrame)
    }

    /**
     * I420(Y·U·V) 평면. 각 평면은 줄마다 stride바이트(패딩 포함)이고, U·V는 가로·세로 절반(올림)이다.
     * NV12(하드웨어 디코더 출력)는 UV를 U·V로 나눠 담는다. [bt709]·[fullRange]는 스트림의 색 공간 정보다.
     */
    class YuvFrame(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val yStride: Int,
        val u: ByteArray,
        val v: ByteArray,
        /** U·V 평면의 줄 길이(같다) */
        val cStride: Int,
        val bt709: Boolean,
        val fullRange: Boolean,
    )

    /**
     * 설정하면 NV12·YUV420P 프레임은 BGRA로 바꾸지 않고 평면 그대로 넘긴다(GPU에서 변환). 다른 형식은 [FrameSink]로 간다.
     */
    var yuvSink: YuvSink? = null
    private val planes = arrayOf(ByteArray(0), ByteArray(0), ByteArray(0))
    private var vPlane = ByteArray(0)

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
        val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
        // 하드웨어로 열리지 않으면(장치는 있지만 프로파일 미지원·드라이버 문제 등) 소프트웨어로 다시 연다.
        val opened = codec?.let { open(it, hardware) ?: if (hardware) open(it, false) else null }
        if (opened == null) {
            // init에서 던지면 close()가 불리지 않으므로 이미 잡은 버퍼를 여기서 푼다.
            releaseBuffers()
            error(if (codec == null) "FFmpeg H.264 디코더가 없습니다" else "H.264 디코더를 열지 못했습니다")
        }
        context = opened
    }

    /** 새 컨텍스트를 만들어 연다. 실패하면 컨텍스트(붙인 하드웨어 장치 참조 포함)를 버리고 null. */
    private fun open(codec: AVCodec, hardware: Boolean): AVCodecContext? {
        val ctx = avcodec.avcodec_alloc_context3(codec) ?: return null
        ctx.flags(ctx.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        ctx.thread_type(FF_THREAD_SLICE)
        ctx.thread_count(0)
        hwPixFmt = -1
        backend = "software"
        if (hardware) attachHardware(ctx, codec)
        if (openCodec(ctx, codec) >= 0) return ctx
        avcodec.avcodec_free_context(ctx)
        hwPixFmt = -1
        backend = "software"
        return null
    }

    /**
     * 이 OS의 하드웨어 장치를 순서대로 열어 본다. 붙이면 FFmpeg 기본 get_format이 하드웨어 형식을 고른다.
     * 모두 실패하면 아무것도 하지 않는다(소프트웨어).
     */
    private fun attachHardware(ctx: AVCodecContext, codec: AVCodec) {
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
            ctx.hw_device_ctx(avutil.av_buffer_ref(device))
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
        yuvSink?.let { yuv -> if (sendYuv(src, w, h, yuv)) return }
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

    /** NV12·YUV420P면 평면을 복사해 [sink]로 넘기고 true. 다른 형식이면 false(BGRA로 변환). */
    private fun sendYuv(src: AVFrame, w: Int, h: Int, sink: YuvSink): Boolean {
        val fmt = src.format()
        val nv12 = fmt == avutil.AV_PIX_FMT_NV12
        val i420 = fmt == avutil.AV_PIX_FMT_YUV420P || fmt == avutil.AV_PIX_FMT_YUVJ420P
        if (!nv12 && !i420) return false
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val ySize = src.linesize(0) * h
        if (planes[0].size != ySize) planes[0] = ByteArray(ySize)
        src.data(0).position(0).get(planes[0], 0, ySize)
        val cStride: Int
        if (nv12) {
            // UV가 번갈아 들어 있다. U·V 평면으로 나눈다(Skia 래스터 이미지가 2채널 형식을 받지 않음).
            cStride = cw
            val uvStride = src.linesize(1)
            val uvSize = uvStride * ch
            if (planes[2].size < uvSize) planes[2] = ByteArray(uvSize) // 교차 평면을 잠시 담는 곳
            if (planes[1].size != cw * ch) planes[1] = ByteArray(cw * ch)
            val uv = planes[2]
            src.data(1).position(0).get(uv, 0, uvSize)
            val u = planes[1]
            if (vPlane.size != cw * ch) vPlane = ByteArray(cw * ch)
            val v = vPlane
            for (row in 0 until ch) {
                var s = row * uvStride
                var o = row * cw
                for (x in 0 until cw) {
                    u[o] = uv[s]
                    v[o] = uv[s + 1]
                    s += 2
                    o++
                }
            }
        } else {
            cStride = src.linesize(1)
            val cSize = cStride * ch
            if (planes[1].size != cSize) planes[1] = ByteArray(cSize)
            if (vPlane.size != cSize) vPlane = ByteArray(cSize)
            src.data(1).position(0).get(planes[1], 0, cSize)
            src.data(2).position(0).get(vPlane, 0, cSize)
        }
        sink.onFrame(
            YuvFrame(
                w, h,
                planes[0], src.linesize(0),
                planes[1], vPlane, cStride,
                bt709 = src.colorspace() == avutil.AVCOL_SPC_BT709,
                fullRange = src.color_range() == avutil.AVCOL_RANGE_JPEG || fmt == avutil.AV_PIX_FMT_YUVJ420P,
            ),
        )
        return true
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
        releaseBuffers()
        avcodec.avcodec_free_context(context)
    }

    private fun releaseBuffers() {
        avutil.av_frame_free(bgraFrame)
        avutil.av_frame_free(swFrame)
        avutil.av_frame_free(frame)
        avcodec.av_packet_free(packet)
    }
}
