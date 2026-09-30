package io.github.eoeo0326.adbmirror.core.data.conversion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 단색 VP8L 프레임 3장으로 애니메이션 WebP를 만들고, 결과를 다시 파싱해 컨테이너 구조를 검증한다.
 * JVM에는 WebP 디코더가 없어 픽셀 디코딩은 하지 않는다. 생성 파일(build/fixture-webp/)은
 * macOS ImageIO로 직접 열어 볼 수 있다: `swift scripts/check_animated_image.swift <file>`
 */
class AnimatedWebpFileTest {
    /**
     * 테스트 전용 최소 VP8L(무손실 WebP) 인코더: 단색 이미지만 만든다.
     * 다섯 prefix code가 모두 기호 하나짜리 simple code라 픽셀마다 0비트가 들어간다.
     */
    private fun solidVp8l(w: Int, h: Int, argb: Int): ByteArray {
        val bits = ArrayList<Boolean>()
        fun put(v: Int, n: Int) = repeat(n) { bits += (v ushr it) and 1 == 1 }
        put(w - 1, 14); put(h - 1, 14); put(0, 1); put(0, 3) // 크기, alpha_is_used=0, version 0
        put(0, 1) // transform 없음
        put(0, 1) // color cache 없음
        put(0, 1) // meta prefix code 없음
        fun simple8(symbol: Int) { put(1, 1); put(0, 1); put(1, 1); put(symbol, 8) } // simple, 기호 1개, 8비트
        simple8((argb ushr 8) and 0xFF) // green
        simple8((argb ushr 16) and 0xFF) // red
        simple8(argb and 0xFF) // blue
        simple8(0xFF) // alpha
        put(1, 1); put(0, 1); put(0, 1); put(0, 1) // distance: 기호 0 하나(1비트 표기)
        val bytes = ByteArray((bits.size + 7) / 8)
        bits.forEachIndexed { i, b -> if (b) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte() }
        val payload = byteArrayOf(0x2F) + bytes
        val pad = payload.size and 1
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        return "RIFF".encodeToByteArray() + le32(4 + 8 + payload.size + pad) + "WEBP".encodeToByteArray() +
            "VP8L".encodeToByteArray() + le32(payload.size) + payload + ByteArray(pad)
    }

    private class Chunk(val fourcc: String, val start: Int, val payload: ByteArray)

    private fun le32(b: ByteArray, at: Int) = (0 until 4).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }
    private fun le24(b: ByteArray, at: Int) = (0 until 3).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }

    /** [from, to) 구간의 RIFF 청크를 순서대로 읽는다. 패딩까지 정확히 떨어지지 않으면 실패한다. */
    private fun chunks(b: ByteArray, from: Int, to: Int): List<Chunk> {
        val list = mutableListOf<Chunk>()
        var p = from
        while (p < to) {
            val size = le32(b, p + 4)
            list += Chunk(b.copyOfRange(p, p + 4).decodeToString(), p, b.copyOfRange(p + 8, p + 8 + size))
            p += 8 + size + (size and 1)
        }
        assertEquals(to, p, "청크가 구간 끝에 정확히 맞지 않는다")
        return list
    }

    @Test
    fun animatedWebpStructureRoundTrips() {
        val colors = listOf(0xFFE5484D.toInt(), 0xFF2FB38A.toInt(), 0xFF17212B.toInt())
        val stills = colors.map { solidVp8l(64, 48, it) }
        val durations = listOf(200, 120, 350)
        val muxer = AnimatedWebpMuxer(64, 48, loopCount = 0)
        stills.forEachIndexed { i, still -> muxer.addFrame(still, durationMs = durations[i]) }
        val webp = muxer.finish()

        assertEquals("RIFF", webp.copyOfRange(0, 4).decodeToString())
        assertEquals(webp.size - 8, le32(webp, 4))
        val top = chunks(webp, 12, webp.size)
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF", "ANMF"), top.map { it.fourcc })
        val vp8x = top[0].payload
        assertEquals(0x02, vp8x[0].toInt()) // animation, 알파 없음
        assertEquals(63, le24(vp8x, 4))
        assertEquals(47, le24(vp8x, 7))

        top.drop(2).forEachIndexed { i, anmf ->
            val f = anmf.payload
            assertEquals(0, le24(f, 0)); assertEquals(0, le24(f, 3)) // 위치
            assertEquals(63, le24(f, 6)); assertEquals(47, le24(f, 9)) // 크기
            assertEquals(durations[i], le24(f, 12))
            assertEquals(0x02, f[15].toInt())
            // ANMF 안 이미지 청크가 원본 한 장짜리 WebP의 VP8L 청크와 바이트 단위로 같아야 한다.
            val inner = chunks(f, 16, f.size).single()
            assertEquals("VP8L", inner.fourcc)
            val original = chunks(stills[i], 12, stills[i].size).single()
            assertContentEquals(original.payload, inner.payload)
        }

        File("build/fixture-webp").mkdirs()
        File("build/fixture-webp/solid-3frames.webp").writeBytes(webp)
        File("build/fixture-webp/solid-still.webp").writeBytes(stills[0])
    }
}
