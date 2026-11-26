package io.github.eoeo0326.adbmirror.core.data.conversion

/**
 * 출력 비교용 참조 구현. [ColorQuantizer]·[GifLzw]는 이것과 팔레트·인덱스·LZW 바이트가 한 바이트도 달라서는 안 된다.
 * 느리지만 읽기 쉬운 형태(컬렉션·HashMap)로 둔다.
 */
internal object ReferenceColorQuantizer {
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

/** 출력 비교용 참조 구현. [GifLzw]는 이것과 바이트 단위로 같은 결과를 내야 한다. */
internal object ReferenceGifLzw {
    private const val MAX_CODES = 4096

    fun encode(indices: ByteArray, minCodeSize: Int = 8): ByteArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val bits = BitPacker()
        var codeSize = minCodeSize + 1
        var nextCode = end + 1
        val table = HashMap<Int, Int>() // (prefix code shl 8) or byte → code

        bits.write(clear, codeSize)
        if (indices.isEmpty()) {
            bits.write(end, codeSize)
            return bits.subBlocks()
        }
        var prefix = indices[0].toInt() and 0xFF
        for (k in 1 until indices.size) {
            val c = indices[k].toInt() and 0xFF
            val key = (prefix shl 8) or c
            val existing = table[key]
            if (existing != null) {
                prefix = existing
                continue
            }
            bits.write(prefix, codeSize)
            if (nextCode < MAX_CODES) {
                table[key] = nextCode++
                if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                bits.write(clear, codeSize)
                table.clear()
                codeSize = minCodeSize + 1
                nextCode = end + 1
            }
            prefix = c
        }
        bits.write(prefix, codeSize)
        bits.write(end, codeSize)
        return bits.subBlocks()
    }

    /** LSB 우선으로 비트를 쌓는다(GIF 규칙). */
    private class BitPacker {
        private var out = ByteArray(1024)
        private var size = 0
        private var acc = 0L
        private var accBits = 0

        fun write(code: Int, width: Int) {
            acc = acc or (code.toLong() shl accBits)
            accBits += width
            while (accBits >= 8) {
                push((acc and 0xFF).toInt())
                acc = acc ushr 8
                accBits -= 8
            }
        }

        private fun push(b: Int) {
            if (size == out.size) out = out.copyOf(out.size * 2)
            out[size++] = b.toByte()
        }

        fun subBlocks(): ByteArray {
            if (accBits > 0) { push((acc and 0xFF).toInt()); acc = 0; accBits = 0 }
            val blocks = (size + 254) / 255
            val result = ByteArray(size + blocks + 1)
            var p = 0
            var i = 0
            while (i < size) {
                val n = minOf(255, size - i)
                result[p++] = n.toByte()
                out.copyInto(result, p, i, i + n)
                p += n
                i += n
            }
            result[p] = 0 // block terminator
            return result
        }
    }
}
