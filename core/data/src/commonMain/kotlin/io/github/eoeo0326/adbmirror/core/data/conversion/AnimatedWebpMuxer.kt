package io.github.eoeo0326.adbmirror.core.data.conversion

/**
 * 한 장짜리 WebP 파일들을 애니메이션 WebP(RIFF: VP8X · ANIM · ANMF…)로 묶는다.
 * 프레임 압축은 플랫폼 인코더(Desktop FFmpeg libwebp, Android Bitmap.compress, Web canvas)가 하고,
 * 이 클래스는 컨테이너만 만든다. RIFF 필드는 little-endian이다.
 */
class AnimatedWebpMuxer(
    private val width: Int,
    private val height: Int,
    /** 재생 횟수(ANIM loop count). 0이면 무한 반복. */
    private val loopCount: Int = 0,
    /** 캔버스 배경색(ARGB). 프레임이 캔버스를 모두 덮으므로 보통 쓰이지 않는다. */
    private val backgroundArgb: Int = 0xFF000000.toInt(),
) {
    private class Frame(val chunks: ByteArray, val hasAlpha: Boolean, val durationMs: Int)

    private val frames = mutableListOf<Frame>()

    init {
        require(width in 1..MAX_DIM && height in 1..MAX_DIM) { "WebP 캔버스 범위 초과: ${width}x$height" }
        require(loopCount in 0..0xFFFF)
    }

    /**
     * [webp]는 캔버스와 같은 크기의 한 장짜리 WebP 파일(RIFF 전체)이다.
     * 그 안의 ALPH·VP8·VP8L 청크만 꺼내 ANMF에 넣는다.
     */
    fun addFrame(webp: ByteArray, durationMs: Int) {
        require(durationMs in 1..0xFFFFFF) { "프레임 길이 범위 초과: $durationMs" }
        val chunks = readChunks(webp)
        val image = chunks.filter { it.fourcc in IMAGE_CHUNKS }
        require(image.any { it.fourcc == "VP8 " || it.fourcc == "VP8L" }) { "VP8/VP8L 이미지 청크가 없다" }
        val out = Buffer()
        image.forEach { out.chunk(it.fourcc, it.payload) }
        val alpha = image.any { it.fourcc == "ALPH" } ||
            image.firstOrNull { it.fourcc == "VP8L" }?.let { vp8lHasAlpha(it.payload) } == true
        frames += Frame(out.toByteArray(), alpha, durationMs)
    }

    fun finish(): ByteArray {
        check(frames.isNotEmpty()) { "프레임이 없다" }
        val body = Buffer()
        body.ascii("WEBP")
        body.chunk("VP8X", Buffer().apply {
            val alphaFlag = if (frames.any { it.hasAlpha }) 0x10 else 0
            u8(0x02 or alphaFlag) // animation
            u24le(0)
            u24le(width - 1); u24le(height - 1)
        }.toByteArray())
        // ARGB 정수를 little-endian으로 쓰면 규격의 [B, G, R, A] 바이트 순서가 된다.
        body.chunk("ANIM", Buffer().apply { u32le(backgroundArgb); u16le(loopCount) }.toByteArray())
        frames.forEach { f ->
            body.chunk("ANMF", Buffer().apply {
                u24le(0); u24le(0) // X/2, Y/2
                u24le(width - 1); u24le(height - 1)
                u24le(f.durationMs)
                u8(0x02) // blending: 겹치지 않고 덮어씀(no blend), disposal: 없음
                bytes(f.chunks)
            }.toByteArray())
        }
        val payload = body.toByteArray()
        return Buffer().apply { ascii("RIFF"); u32le(payload.size); bytes(payload) }.toByteArray()
    }

    private class Chunk(val fourcc: String, val payload: ByteArray)

    private fun readChunks(webp: ByteArray): List<Chunk> {
        require(webp.size >= 12 && webp.ascii(0) == "RIFF" && webp.ascii(8) == "WEBP") { "WebP RIFF 파일이 아니다" }
        val end = minOf(webp.size, 8 + webp.u32le(4))
        val chunks = mutableListOf<Chunk>()
        var p = 12
        while (p + 8 <= end) {
            val size = webp.u32le(p + 4)
            require(p + 8 + size <= end) { "청크가 파일 밖으로 나간다" }
            chunks += Chunk(webp.ascii(p), webp.copyOfRange(p + 8, p + 8 + size))
            p += 8 + size + (size and 1)
        }
        return chunks
    }

    /** VP8L 헤더(시그니처 1B + 14·14비트 크기) 다음의 alpha_is_used 비트 */
    private fun vp8lHasAlpha(payload: ByteArray): Boolean =
        payload.size >= 5 && (payload[4].toInt() ushr 4) and 1 == 1

    private fun ByteArray.ascii(at: Int) = (0 until 4).map { this[at + it].toInt().toChar() }.joinToString("")
    private fun ByteArray.u32le(at: Int) = (0 until 4).sumOf { (this[at + it].toInt() and 0xFF) shl (8 * it) }

    private class Buffer {
        private var data = ByteArray(1024)
        private var size = 0
        fun u8(v: Int) { if (size == data.size) data = data.copyOf(data.size * 2); data[size++] = v.toByte() }
        fun u16le(v: Int) { u8(v); u8(v ushr 8) }
        fun u24le(v: Int) { u16le(v); u8(v ushr 16) }
        fun u32le(v: Int) { u16le(v); u16le(v ushr 16) }
        fun ascii(s: String) = s.forEach { u8(it.code) }
        fun bytes(b: ByteArray) { if (size + b.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + b.size)); b.copyInto(data, size); size += b.size }
        fun chunk(fourcc: String, payload: ByteArray) {
            ascii(fourcc); u32le(payload.size); bytes(payload)
            if (payload.size and 1 == 1) u8(0) // 청크는 짝수 길이로 패딩
        }
        fun toByteArray() = data.copyOf(size)
    }

    companion object {
        private const val MAX_DIM = 1 shl 24
        private val IMAGE_CHUNKS = setOf("ALPH", "VP8 ", "VP8L")
    }
}
