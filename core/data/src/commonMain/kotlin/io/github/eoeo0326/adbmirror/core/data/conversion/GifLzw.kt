package io.github.eoeo0326.adbmirror.core.data.conversion

/** GIF용 가변 길이 LZW 압축(최대 12비트 코드). 결과는 255바이트 이하 sub-block으로 나뉜 image data. */
internal object GifLzw {
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
