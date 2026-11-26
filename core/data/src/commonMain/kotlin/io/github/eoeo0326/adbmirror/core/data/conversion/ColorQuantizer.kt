package io.github.eoeo0326.adbmirror.core.data.conversion

/**
 * median cut 팔레트 양자화. 프레임마다 최대 256색 팔레트를 만들고 각 픽셀을 팔레트 인덱스로 바꾼다.
 * 알파는 쓰지 않는다(화면 캡처는 불투명).
 */
internal class ColorQuantizer {
    class Result(val palette: IntArray, val indices: ByteArray)

    /** 프레임에서 고른 샘플. median cut이 상자 구간마다 제자리에서 정렬한다. 큰 프레임이 오면 키운다. */
    private var sample = IntArray(0)

    /** 계수 정렬 결과를 잠시 담는다. [sample]과 크기가 같다. */
    private var sorted = IntArray(0)
    private val count = IntArray(257)

    /** 24비트 색마다 1비트. 샘플에서 처음 나온 색을 가린다. 쓰고 나면 켠 비트를 모두 끈다. */
    private val seen = LongArray((1 shl 24) / 64)

    fun quantize(frame: RgbaFrame, maxColors: Int = 256, dither: Boolean = false): Result {
        require(maxColors >= 1) { "maxColors는 1 이상이어야 한다: $maxColors" }
        val palette = buildPalette(frame.pixels, maxColors)
        val map = NearestColor(palette)
        val indices = if (dither) ditherIndices(frame, palette, map) else ByteArray(frame.pixels.size) { map.index(frame.pixels[it]).toByte() }
        return Result(palette, indices)
    }

    private fun buildPalette(pixels: IntArray, maxColors: Int): IntArray {
        val stride = maxOf(1, pixels.size / MAX_SAMPLES)
        val n = (pixels.size + stride - 1) / stride
        if (sample.size < n) {
            sample = IntArray(n)
            sorted = IntArray(n)
        }
        val sample = sample
        for (i in 0 until n) sample[i] = pixels[i * stride] and 0xFFFFFF
        distinctColors(sample, n, maxColors)?.let { return it }

        // 상자는 sample의 [start, end) 구간이다. 고른 상자는 목록에서 빼고 두 반쪽을 끝에 붙인다. 이 목록 순서가 팔레트 순서다.
        // 채널(shift)과 값 범위는 상자를 만들 때 한 번만 잰다.
        val start = IntArray(maxColors)
        val end = IntArray(maxColors)
        val shift = IntArray(maxColors)
        val range = IntArray(maxColors)
        var boxes = 0
        fun add(from: Int, to: Int) {
            val widest = widestChannel(sample, from, to)
            start[boxes] = from
            end[boxes] = to
            shift[boxes] = widest and 0xFF
            range[boxes] = widest ushr 8
            boxes++
        }
        add(0, n)
        while (boxes < maxColors) {
            // 두 개 이상 든 상자 중 범위가 가장 넓은 것. 동점이면 앞 상자.
            var best = -1
            for (i in 0 until boxes) if (end[i] - start[i] > 1 && (best < 0 || range[i] > range[best])) best = i
            if (best < 0) break
            val from = start[best]
            val to = end[best]
            val s = shift[best]
            start.copyInto(start, best, best + 1, boxes)
            end.copyInto(end, best, best + 1, boxes)
            shift.copyInto(shift, best, best + 1, boxes)
            range.copyInto(range, best, best + 1, boxes)
            boxes--
            sortByChannel(sample, from, to, s)
            val mid = from + (to - from) / 2
            add(from, mid)
            add(mid, to)
        }
        return IntArray(boxes) { average(sample, start[it], end[it]) }
    }

    /** 샘플 앞 [n]개의 색이 [maxColors]가지 이하면 처음 나온 순서대로 돌려준다. 넘으면 그 자리에서 멈추고 null. */
    private fun distinctColors(sample: IntArray, n: Int, maxColors: Int): IntArray? {
        val found = IntArray(minOf(n, maxColors + 1))
        var count = 0
        for (i in 0 until n) {
            val c = sample[i]
            val word = c ushr 6
            val bit = 1L shl (c and 63)
            if (seen[word] and bit == 0L) {
                seen[word] = seen[word] or bit
                found[count++] = c
                if (count > maxColors) break
            }
        }
        for (i in 0 until count) seen[found[i] ushr 6] = 0L
        return if (count <= maxColors) found.copyOf(count) else null
    }

    /**
     * [from, to) 구간에서 값 범위가 가장 넓은 채널. `(범위 shl 8) or 채널 shift`로 돌려준다.
     * 동점이면 앞 채널(R → G → B)을 고른다.
     */
    private fun widestChannel(box: IntArray, from: Int, to: Int): Int {
        var loR = 255
        var hiR = 0
        var loG = 255
        var hiG = 0
        var loB = 255
        var hiB = 0
        for (i in from until to) {
            val c = box[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            if (r < loR) loR = r
            if (r > hiR) hiR = r
            if (g < loG) loG = g
            if (g > hiG) hiG = g
            if (b < loB) loB = b
            if (b > hiB) hiB = b
        }
        var bestShift = 16
        var bestRange = hiR - loR
        if (hiG - loG > bestRange) { bestShift = 8; bestRange = hiG - loG }
        if (hiB - loB > bestRange) { bestShift = 0; bestRange = hiB - loB }
        return (bestRange shl 8) or bestShift
    }

    /** [from, to) 구간을 [shift] 채널 값으로 정렬한다. 값이 8비트라 256칸 계수 정렬을 쓰고, 같은 값끼리는 순서를 지킨다(안정 정렬). */
    private fun sortByChannel(box: IntArray, from: Int, to: Int, shift: Int) {
        count.fill(0)
        for (i in from until to) count[((box[i] ushr shift) and 0xFF) + 1]++
        for (v in 1..256) count[v] += count[v - 1]
        for (i in from until to) {
            val c = box[i]
            sorted[count[(c ushr shift) and 0xFF]++] = c
        }
        sorted.copyInto(box, from, 0, to - from)
    }

    private fun average(box: IntArray, from: Int, to: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        for (i in from until to) {
            val c = box[i]
            r += (c ushr 16) and 0xFF; g += (c ushr 8) and 0xFF; b += c and 0xFF
        }
        val n = to - from
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

    private companion object {
        const val MAX_SAMPLES = 65_536
    }
}
