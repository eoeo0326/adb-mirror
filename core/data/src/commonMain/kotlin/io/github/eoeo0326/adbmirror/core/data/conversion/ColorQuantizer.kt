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

    private val nearest = NearestColor()

    /** 디더링 오차 두 줄(지금 줄·다음 줄 × R·G·B 각 너비만큼). 넓은 프레임이 오면 키운다. */
    private var err = FloatArray(0)

    fun quantize(frame: RgbaFrame, maxColors: Int = 256, dither: Boolean = false): Result {
        require(maxColors in 1..256) { "maxColors는 1..256이어야 한다: $maxColors" }
        val palette = buildPalette(frame.pixels, maxColors)
        val map = nearest.reset(palette)
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
        // 오차는 지금 줄과 다음 줄에만 쌓이므로 두 줄을 번갈아 쓴다. 한 줄은 R·G·B 순서로 w칸씩이다.
        if (err.size < 6 * w) err = FloatArray(6 * w)
        val err = err
        err.fill(0f, 0, 6 * w)
        var row = 0
        var below = 3 * w
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val hasBelow = y + 1 < h
            for (x in 0 until w) {
                val i = y * w + x
                val c = frame.pixels[i]
                val r = (((c ushr 16) and 0xFF) + err[row + x]).toInt().coerceIn(0, 255)
                val g = (((c ushr 8) and 0xFF) + err[row + w + x]).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) + err[row + 2 * w + x]).toInt().coerceIn(0, 255)
                val idx = map.index((r shl 16) or (g shl 8) or b)
                out[i] = idx.toByte()
                val p = palette[idx]
                spread(err, row, below, w, x, hasBelow, (r - ((p ushr 16) and 0xFF)).toFloat())
                spread(err, row + w, below + w, w, x, hasBelow, (g - ((p ushr 8) and 0xFF)).toFloat())
                spread(err, row + 2 * w, below + 2 * w, w, x, hasBelow, (b - (p and 0xFF)).toFloat())
            }
            // 다 쓴 줄을 비우고 다음 줄의 아래 줄로 쓴다.
            err.fill(0f, row, row + 3 * w)
            row = below.also { below = row }
        }
        return out
    }

    /** 한 채널의 오차 [e]를 오른쪽 칸(지금 줄 [row])과 아래 세 칸(다음 줄 [below])에 나눈다. */
    private fun spread(err: FloatArray, row: Int, below: Int, w: Int, x: Int, hasBelow: Boolean, e: Float) {
        if (x + 1 < w) err[row + x + 1] += e * 7 / 16
        if (hasBelow) {
            if (x > 0) err[below + x - 1] += e * 3 / 16
            err[below + x] += e * 5 / 16
            if (x + 1 < w) err[below + x + 1] += e * 1 / 16
        }
    }

    /**
     * 가장 가까운 팔레트 색(RGB 제곱 거리). 거리가 같으면 앞 인덱스를 고른다.
     * 한 번 찾은 색은 열린 주소 표에 캐시한다. 표가 반쯤 차면 더 넣지 않고 그때그때 찾는다(결과는 같다).
     */
    private class NearestColor {
        private var palette = IntArray(0)

        /** 24비트 색. -1은 빈 칸이다(색은 음수가 아니다). */
        private val keys = IntArray(CAPACITY).also { it.fill(-1) }
        private val values = ByteArray(CAPACITY)

        /** [keys]에 넣은 칸. 팔레트가 바뀌면 이 칸만 비운다. */
        private val filled = IntArray(CAPACITY / 2)
        private var filledCount = 0

        fun reset(palette: IntArray): NearestColor {
            this.palette = palette
            for (i in 0 until filledCount) keys[filled[i]] = -1
            filledCount = 0
            return this
        }

        fun index(color: Int): Int {
            val rgb = color and 0xFFFFFF
            var slot = (rgb * HASH) ushr (32 - BITS)
            while (true) {
                val key = keys[slot]
                if (key == rgb) return values[slot].toInt() and 0xFF
                if (key == -1) break
                slot = (slot + 1) and (CAPACITY - 1)
            }
            val best = search(rgb)
            if (filledCount < filled.size) {
                keys[slot] = rgb
                values[slot] = best.toByte()
                filled[filledCount++] = slot
            }
            return best
        }

        private fun search(rgb: Int): Int {
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
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
            return best
        }

        private companion object {
            const val BITS = 18
            const val CAPACITY = 1 shl BITS
            const val HASH = 0x9E3779B1.toInt()
        }
    }

    private companion object {
        const val MAX_SAMPLES = 65_536
    }
}
