package io.github.eoeo0326.adbmirror.core.data.conversion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 단색 VP8L 프레임 3장으로 애니메이션 WebP를 만들어 build/fixture-webp/에 남긴다.
 * 파일이 실제로 열리는지는 macOS ImageIO로 확인한다: `swift scripts/check_animated_image.swift <file>`
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

    @Test
    fun writesAnimatedWebp() {
        val colors = listOf(0xFFE5484D.toInt(), 0xFF2FB38A.toInt(), 0xFF17212B.toInt())
        val muxer = AnimatedWebpMuxer(64, 48, loopCount = 0)
        colors.forEach { muxer.addFrame(solidVp8l(64, 48, it), durationMs = 200) }
        val webp = muxer.finish()
        File("build/fixture-webp").mkdirs()
        File("build/fixture-webp/solid-3frames.webp").writeBytes(webp)
        File("build/fixture-webp/solid-still.webp").writeBytes(solidVp8l(64, 48, colors[0]))
        assertTrue(webp.size > 100)
    }
}
