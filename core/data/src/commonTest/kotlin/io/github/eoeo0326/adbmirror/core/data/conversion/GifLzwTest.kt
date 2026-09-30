package io.github.eoeo0326.adbmirror.core.data.conversion

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class GifLzwTest {
    /** 테스트용 GIF LZW 디코더(표준 규칙). */
    private fun decode(blocks: ByteArray, minCodeSize: Int = 8): ByteArray {
        val data = mutableListOf<Byte>()
        var p = 0
        while (true) {
            val n = blocks[p++].toInt() and 0xFF
            if (n == 0) break
            repeat(n) { data += blocks[p++] }
        }
        val clear = 1 shl minCodeSize
        val end = clear + 1
        var codeSize = minCodeSize + 1
        val dict = ArrayList<ByteArray>()
        fun reset() {
            dict.clear()
            for (i in 0 until clear) dict += byteArrayOf(i.toByte())
            dict += ByteArray(0); dict += ByteArray(0)
            codeSize = minCodeSize + 1
        }
        reset()
        val out = ArrayList<Byte>()
        var bitPos = 0
        fun read(): Int {
            var v = 0
            for (i in 0 until codeSize) {
                val byte = data[(bitPos + i) / 8].toInt()
                v = v or (((byte ushr ((bitPos + i) % 8)) and 1) shl i)
            }
            bitPos += codeSize
            return v
        }
        var prev: ByteArray? = null
        while (true) {
            val code = read()
            if (code == clear) { reset(); prev = null; continue }
            if (code == end) break
            val entry = when {
                code < dict.size -> dict[code]
                code == dict.size && prev != null -> prev + prev[0]
                else -> error("잘못된 코드 $code")
            }
            out.addAll(entry.toList())
            if (prev != null && dict.size < 4096) dict += prev + entry[0]
            if (dict.size == (1 shl codeSize) && codeSize < 12) codeSize++
            prev = entry
        }
        return out.toByteArray()
    }

    @Test
    fun roundTripsSmallInput() {
        val input = byteArrayOf(1, 1, 1, 2, 2, 1, 1, 1, 3)
        assertContentEquals(input, decode(GifLzw.encode(input)))
    }

    @Test
    fun roundTripsAcrossTableResets() {
        val input = ByteArray(200_000) { Random(7).nextInt(0, 256).toByte() }
        val random = Random(42)
        for (i in input.indices) input[i] = random.nextInt(0, 256).toByte()
        assertContentEquals(input, decode(GifLzw.encode(input)))
    }

    @Test
    fun roundTripsRepetitiveInput() {
        val input = ByteArray(100_000) { (it / 1000 % 5).toByte() }
        assertContentEquals(input, decode(GifLzw.encode(input)))
    }

    @Test
    fun emptyInput() {
        assertContentEquals(ByteArray(0), decode(GifLzw.encode(ByteArray(0))))
    }
}
