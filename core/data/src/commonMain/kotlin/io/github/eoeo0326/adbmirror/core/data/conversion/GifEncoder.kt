package io.github.eoeo0326.adbmirror.core.data.conversion

/**
 * 애니메이션 GIF89a 인코더. 프레임마다 256색 로컬 팔레트를 만든다.
 *
 * GIF 지연 시간 단위는 1/100초다. 대부분의 브라우저가 2(20ms) 미만을 10(100ms)으로 바꾸므로 최소 2로 맞춘다.
 */
class GifEncoder(
    private val width: Int,
    private val height: Int,
    /** 0이면 무한 반복 */
    private val loopCount: Int = 0,
    private val dither: Boolean = false,
) {
    private val out = Buffer()
    private var frames = 0
    private var finished = false

    init {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "GIF 크기 범위 초과: ${width}x$height" }
        require(loopCount in 0..0xFFFF)
        out.ascii("GIF89a")
        out.u16le(width); out.u16le(height)
        out.u8(0x70) // 전역 팔레트 없음, color resolution 8비트
        out.u8(0); out.u8(0) // background, aspect
        // NETSCAPE2.0 반복 확장
        out.u8(0x21); out.u8(0xFF); out.u8(11); out.ascii("NETSCAPE2.0")
        out.u8(3); out.u8(1); out.u16le(loopCount); out.u8(0)
    }

    fun addFrame(frame: RgbaFrame, delayMs: Int) {
        check(!finished)
        require(frame.width == width && frame.height == height) { "프레임 크기가 캔버스와 다르다" }
        val q = ColorQuantizer.quantize(frame, dither = dither)
        val centis = ((delayMs + 5) / 10).coerceIn(2, 0xFFFF)

        out.u8(0x21); out.u8(0xF9); out.u8(4) // Graphic Control Extension
        out.u8(0x04) // disposal 1(그대로 둠), 투명색 없음
        out.u16le(centis)
        out.u8(0); out.u8(0)

        out.u8(0x2C) // Image Descriptor
        out.u16le(0); out.u16le(0); out.u16le(width); out.u16le(height)
        out.u8(0x80 or 7) // 로컬 팔레트 사용, 크기 2^(7+1) = 256
        for (i in 0 until 256) {
            val c = if (i < q.palette.size) q.palette[i] else 0
            out.u8((c ushr 16) and 0xFF); out.u8((c ushr 8) and 0xFF); out.u8(c and 0xFF)
        }
        out.u8(8) // LZW 최소 코드 크기
        out.bytes(GifLzw.encode(q.indices, 8))
        frames++
    }

    fun finish(): ByteArray {
        check(frames > 0) { "프레임이 없다" }
        if (!finished) { out.u8(0x3B); finished = true }
        return out.toByteArray()
    }

    private class Buffer {
        private var data = ByteArray(4096)
        private var size = 0
        fun u8(v: Int) { if (size == data.size) data = data.copyOf(data.size * 2); data[size++] = v.toByte() }
        fun u16le(v: Int) { u8(v); u8(v ushr 8) }
        fun ascii(s: String) = s.forEach { u8(it.code) }
        fun bytes(b: ByteArray) { if (size + b.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + b.size)); b.copyInto(data, size); size += b.size }
        fun toByteArray() = data.copyOf(size)
    }
}
