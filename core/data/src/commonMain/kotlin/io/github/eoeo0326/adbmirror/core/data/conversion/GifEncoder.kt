package io.github.eoeo0326.adbmirror.core.data.conversion

/**
 * 애니메이션 GIF89a 인코더. 프레임마다 256색 로컬 팔레트를 만든다.
 *
 * - 둘째 프레임부터는 지금 보이는 화면과 달라진 사각형만 쓴다(disposal 1 = 그대로 둠). 화면 녹화는 상태 표시줄·스크롤
 *   영역만 바뀌는 일이 많아 크기가 준다.
 * - 디코딩 잡음으로 채널 값이 [tolerance] 이하로만 흔들린 픽셀은 바뀌지 않은 것으로 본다. 보이는 화면과 비교하므로
 *   오차가 쌓이지 않는다(허용치를 넘는 순간 다시 쓴다).
 * - 보이는 화면과 다르지 않은 프레임은 쓰지 않고 앞 프레임의 지연 시간에 더한다. 그래서 마지막 프레임은 다음 프레임이나
 *   [finish]가 올 때까지 들고 있다.
 *
 * GIF 지연 시간 단위는 1/100초다. 대부분의 브라우저가 2(20ms) 미만을 10(100ms)으로 바꾸므로 최소 2로 맞춘다.
 */
class GifEncoder(
    private val width: Int,
    private val height: Int,
    /** 재생 횟수. 0이면 무한 반복. WebP([AnimatedWebpMuxer])와 뜻이 같다. */
    private val loopCount: Int = 0,
    private val dither: Boolean = false,
    /** 채널(R·G·B)마다 이만큼까지의 차이는 같은 픽셀로 본다. 0이면 정확히 같아야 한다. */
    private val tolerance: Int = 6,
) {
    private val out = Buffer()
    private var frames = 0
    private var finished = false

    /** 아직 쓰지 않은 프레임과 그 지연 시간(ms). 같은 프레임이 이어지면 지연만 늘린다. */
    private var pending: RgbaFrame? = null
    private var pendingDelayMs = 0

    /** 지금까지 쓴 이미지를 겹친 결과(보는 사람에게 보이는 화면). 첫 프레임을 쓰기 전에는 null. */
    private var canvas: IntArray? = null

    init {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "GIF 크기 범위 초과: ${width}x$height" }
        require(loopCount in 0..0xFFFF)
        out.ascii("GIF89a")
        out.u16le(width); out.u16le(height)
        out.u8(0x70) // 전역 팔레트 없음, color resolution 8비트
        out.u8(0); out.u8(0) // background, aspect
        // NETSCAPE2.0 반복 확장: 값은 첫 재생 뒤 더 반복할 횟수(0이면 무한). 한 번만 재생하면 확장을 넣지 않는다.
        if (loopCount != 1) {
            out.u8(0x21); out.u8(0xFF); out.u8(11); out.ascii("NETSCAPE2.0")
            out.u8(3); out.u8(1); out.u16le(if (loopCount == 0) 0 else loopCount - 1); out.u8(0)
        }
    }

    fun addFrame(frame: RgbaFrame, delayMs: Int) {
        check(!finished)
        require(frame.width == width && frame.height == height) { "프레임 크기가 캔버스와 다르다" }
        val p = pending
        if (p != null && changedRect(p.pixels, frame) == null) {
            pendingDelayMs += delayMs
            return
        }
        if (p != null) write(p, pendingDelayMs)
        pending = frame
        pendingDelayMs = delayMs
    }

    private fun write(frame: RgbaFrame, delayMs: Int) {
        val shown = canvas
        // 보이는 화면과 허용치 안에서 같으면(앞 프레임과 잡음만 다름) 1픽셀만 다시 써 지연 시간만 이어 간다.
        val r = if (shown == null) Rect(0, 0, width, height) else changedRect(shown, frame) ?: Rect(0, 0, 1, 1)
        val region = if (r.w == width && r.h == height) frame else crop(frame, r)
        val q = ColorQuantizer.quantize(region, dither = dither)
        // 한 프레임에 65535cs(약 11분)까지. 넘으면 나머지는 같은 영역을 한 번 더 써서 이어 붙인다.
        var remaining = (delayMs + 5) / 10
        do {
            val centis = remaining.coerceIn(2, 0xFFFF)
            remaining -= centis
            writeImage(r, q, centis)
        } while (remaining > 0)
        val c = shown ?: IntArray(width * height).also { canvas = it }
        for (y in r.y until r.y + r.h) frame.pixels.copyInto(c, y * width + r.x, y * width + r.x, y * width + r.x + r.w)
    }

    private fun writeImage(r: Rect, q: ColorQuantizer.Result, centis: Int) {
        out.u8(0x21); out.u8(0xF9); out.u8(4) // Graphic Control Extension
        out.u8(0x04) // disposal 1(그대로 둠), 투명색 없음
        out.u16le(centis)
        out.u8(0); out.u8(0)

        out.u8(0x2C) // Image Descriptor
        out.u16le(r.x); out.u16le(r.y); out.u16le(r.w); out.u16le(r.h)
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
        if (!finished) {
            pending?.let { write(it, pendingDelayMs) }
            pending = null
            check(frames > 0) { "프레임이 없다" }
            out.u8(0x3B)
            finished = true
        }
        return out.toByteArray()
    }

    private class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun differs(a: Int, b: Int): Boolean {
        if (a == b) return false
        if (tolerance == 0) return true
        return kotlin.math.abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)) > tolerance ||
            kotlin.math.abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)) > tolerance ||
            kotlin.math.abs((a and 0xFF) - (b and 0xFF)) > tolerance
    }

    /** [a]와 [b]가 허용치를 넘게 다른 픽셀을 모두 담는 가장 작은 사각형. 다른 곳이 없으면 null. */
    private fun changedRect(a: IntArray, b: RgbaFrame): Rect? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (differs(a[row + x], b.pixels[row + x])) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun crop(frame: RgbaFrame, r: Rect): RgbaFrame {
        val px = IntArray(r.w * r.h)
        for (y in 0 until r.h) frame.pixels.copyInto(px, y * r.w, (r.y + y) * width + r.x, (r.y + y) * width + r.x + r.w)
        return RgbaFrame(r.w, r.h, px)
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
