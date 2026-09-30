package io.github.eoeo0326.adbmirror.core.data.conversion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnimatedWebpMuxerTest {
    private fun le32(b: ByteArray, at: Int) = (0 until 4).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }
    private fun le24(b: ByteArray, at: Int) = (0 until 3).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }
    private fun fourcc(b: ByteArray, at: Int) = b.copyOfRange(at, at + 4).decodeToString()

    /** 가짜 한 장짜리 WebP: VP8L 청크 하나(홀수 길이로 패딩 확인) */
    private fun still(payload: ByteArray): ByteArray {
        val pad = if (payload.size % 2 == 1) 1 else 0
        val riffSize = 4 + 8 + payload.size + pad
        return "RIFF".encodeToByteArray() + byteArrayOf(riffSize.toByte(), (riffSize shr 8).toByte(), 0, 0) +
            "WEBP".encodeToByteArray() + "VP8L".encodeToByteArray() +
            byteArrayOf(payload.size.toByte(), 0, 0, 0) + payload + ByteArray(pad)
    }

    @Test
    fun buildsRiffWithVp8xAnimAndFrames() {
        val frame = still(byteArrayOf(0x2F, 0, 0, 0, 0x10)) // alpha_is_used 비트 켜짐
        val webp = AnimatedWebpMuxer(320, 240, loopCount = 3).apply {
            addFrame(frame, 66)
            addFrame(frame, 100)
        }.finish()

        assertEquals("RIFF", fourcc(webp, 0))
        assertEquals(webp.size - 8, le32(webp, 4))
        assertEquals("WEBP", fourcc(webp, 8))
        assertEquals("VP8X", fourcc(webp, 12))
        assertEquals(0x12, webp[20].toInt()) // animation | alpha
        assertEquals(319, le24(webp, 24))
        assertEquals(239, le24(webp, 27))
        assertEquals("ANIM", fourcc(webp, 30))
        assertEquals(3, (webp[42].toInt() and 0xFF) or ((webp[43].toInt() and 0xFF) shl 8))

        var p = 44
        val durations = mutableListOf<Int>()
        while (p < webp.size) {
            assertEquals("ANMF", fourcc(webp, p))
            val size = le32(webp, p + 4)
            durations += le24(webp, p + 8 + 12)
            assertEquals(2, webp[p + 8 + 15].toInt()) // no blend
            assertEquals("VP8L", fourcc(webp, p + 8 + 16))
            p += 8 + size + (size and 1)
        }
        assertEquals(webp.size, p)
        assertEquals(listOf(66, 100), durations)
    }

    @Test
    fun rejectsNonWebpAndEmpty() {
        assertFailsWith<IllegalArgumentException> { AnimatedWebpMuxer(1, 1).addFrame(ByteArray(12), 10) }
        assertFailsWith<IllegalStateException> { AnimatedWebpMuxer(1, 1).finish() }
    }
}
