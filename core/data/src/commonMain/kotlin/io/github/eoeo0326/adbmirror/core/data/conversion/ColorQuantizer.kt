package io.github.eoeo0326.adbmirror.core.data.conversion

/**
 * median cut 팔레트 양자화. 프레임마다 최대 256색 팔레트를 만들고 각 픽셀을 팔레트 인덱스로 바꾼다.
 * 알파는 쓰지 않는다(화면 캡처는 불투명).
 */
internal object ColorQuantizer {
    private const val MAX_SAMPLES = 65_536

    class Result(val palette: IntArray, val indices: ByteArray)

    fun quantize(frame: RgbaFrame, maxColors: Int = 256, dither: Boolean = false): Result {
        val palette = buildPalette(frame.pixels, maxColors)
        val map = NearestColor(palette)
        val indices = if (dither) ditherIndices(frame, palette, map) else ByteArray(frame.pixels.size) { map.index(frame.pixels[it]).toByte() }
        return Result(palette, indices)
    }

    private fun buildPalette(pixels: IntArray, maxColors: Int): IntArray {
        val stride = maxOf(1, pixels.size / MAX_SAMPLES)
        val sample = IntArray((pixels.size + stride - 1) / stride) { pixels[it * stride] and 0xFFFFFF }
        val distinct = sample.distinct()
        if (distinct.size <= maxColors) return distinct.toIntArray()

        val boxes = mutableListOf(sample)
        while (boxes.size < maxColors) {
            val (index, channel) = boxes.withIndex().filter { it.value.size > 1 }
                .map { (i, box) -> i to widestChannel(box) }
                .maxByOrNull { (_, c) -> c.second } ?: break
            val shift = channel.first
            val box = boxes.removeAt(index).sortedBy { (it ushr shift) and 0xFF }.toIntArray()
            val mid = box.size / 2
            boxes += box.copyOfRange(0, mid)
            boxes += box.copyOfRange(mid, box.size)
        }
        return IntArray(boxes.size) { average(boxes[it]) }
    }

    /** (채널 shift, 값 범위) 중 범위가 가장 넓은 채널 */
    private fun widestChannel(box: IntArray): Pair<Int, Int> =
        listOf(16, 8, 0).map { shift ->
            var lo = 255
            var hi = 0
            for (c in box) {
                val v = (c ushr shift) and 0xFF
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            shift to hi - lo
        }.maxBy { it.second }

    private fun average(box: IntArray): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        for (c in box) {
            r += (c ushr 16) and 0xFF; g += (c ushr 8) and 0xFF; b += c and 0xFF
        }
        val n = box.size
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun ditherIndices(frame: RgbaFrame, palette: IntArray, map: NearestColor): ByteArray {
        val w = frame.width
        val h = frame.height
        // Floyd–Steinberg: 오차를 오른쪽·아래 이웃에 7/16, 3/16, 5/16, 1/16로 나눈다.
        val err = Array(3) { FloatArray(w * h) }
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = frame.pixels[i]
            val rgb = IntArray(3) { ch ->
                val v = (c ushr (16 - ch * 8)) and 0xFF
                (v + err[ch][i]).toInt().coerceIn(0, 255)
            }
            val idx = map.index((rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2])
            out[i] = idx.toByte()
            val p = palette[idx]
            for (ch in 0..2) {
                val e = (rgb[ch] - ((p ushr (16 - ch * 8)) and 0xFF)).toFloat()
                if (x + 1 < w) err[ch][i + 1] += e * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) err[ch][i + w - 1] += e * 3 / 16
                    err[ch][i + w] += e * 5 / 16
                    if (x + 1 < w) err[ch][i + w + 1] += e * 1 / 16
                }
            }
        }
        return out
    }

    /** 가장 가까운 팔레트 색(RGB 제곱 거리). 같은 색은 캐시한다. */
    private class NearestColor(private val palette: IntArray) {
        private val cache = HashMap<Int, Int>()

        fun index(color: Int): Int = cache.getOrPut(color and 0xFFFFFF) {
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in palette.indices) {
                val p = palette[i]
                val dr = r - ((p ushr 16) and 0xFF)
                val dg = g - ((p ushr 8) and 0xFF)
                val db = b - (p and 0xFF)
                val d = dr * dr + dg * dg + db * db
                if (d < bestDist) { bestDist = d; best = i; if (d == 0) break }
            }
            best
        }
    }
}
