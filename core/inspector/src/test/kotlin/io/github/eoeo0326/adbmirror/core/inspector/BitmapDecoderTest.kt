package io.github.eoeo0326.adbmirror.core.inspector

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BitmapDecoderTest {
    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater().apply { setInput(raw); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun bitmap(width: Int, height: Int, format: Int, vararg pixels: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(v: Int) = repeat(4) { out.write((v shr (8 * it)) and 0xff) }
        le(width)
        le(height)
        out.write(format)
        pixels.forEach { out.write(it) }
        return deflate(out.toByteArray())
    }

    @Test
    fun decodesAbgr8888AsRgbaBytesInMemory() {
        // 빨강(불투명), 반투명 파랑
        val shot = BitmapDecoder.decode(7, bitmap(2, 1, BitmapDecoder.ABGR_8888, 0xff, 0, 0, 0xff, 0, 0, 0xff, 0x80))
        assertEquals(7, shot.rootId)
        assertEquals(2, shot.width)
        assertEquals(1, shot.height)
        assertContentEquals(intArrayOf(0xffff0000.toInt(), 0x800000ff.toInt()), shot.argb)
    }

    @Test
    fun decodesArgb8888AsBgraBytesInMemory() {
        val shot = BitmapDecoder.decode(1, bitmap(1, 2, BitmapDecoder.ARGB_8888, 0x11, 0x22, 0x33, 0xff, 0, 0xff, 0, 0x40))
        assertContentEquals(intArrayOf(0xff332211.toInt(), 0x4000ff00), shot.argb)
    }

    @Test
    fun decodesRgb565LittleEndianAndExpandsBits() {
        // 0xf800 = 빨강 최대, 0x07e0 = 초록 최대, 0x001f = 파랑 최대, 0x0000 = 검정
        val shot = BitmapDecoder.decode(1, bitmap(2, 2, BitmapDecoder.RGB_565, 0x00, 0xf8, 0xe0, 0x07, 0x1f, 0x00, 0x00, 0x00))
        assertContentEquals(
            intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xff000000.toInt()),
            shot.argb,
        )
    }

    @Test
    fun rejectsTruncatedOrUnknownData() {
        assertFailsWith<BitmapDecoder.InvalidBitmapException> { BitmapDecoder.decode(1, bitmap(2, 2, BitmapDecoder.ARGB_8888, 1, 2, 3, 4)) }
        assertFailsWith<BitmapDecoder.InvalidBitmapException> { BitmapDecoder.decode(1, bitmap(1, 1, 0, 1, 2, 3, 4)) }
        assertFailsWith<BitmapDecoder.InvalidBitmapException> { BitmapDecoder.decode(1, deflate(byteArrayOf(1, 2, 3))) }
        assertFailsWith<BitmapDecoder.InvalidBitmapException> { BitmapDecoder.decode(1, byteArrayOf(1, 2, 3, 4, 5)) }
    }
}
