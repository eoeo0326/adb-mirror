package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectorScreenshot
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * View inspector의 BITMAP 스크린샷을 ARGB로 푼다. Android Studio `ViewAndroidWindow`와 같은 형식이다.
 *
 * deflate로 압축된 바이트를 풀면 `[0..3]` width, `[4..7]` height(little-endian int), `[8]` 형식,
 * `[9..]` 픽셀이다. 형식 이름은 픽셀을 little-endian 정수로 읽었을 때의 비트 배치다
 * (`ABGR_8888`이면 메모리에 R,G,B,A 순서 — Android `Bitmap.Config.ARGB_8888`의 메모리 배치).
 */
object BitmapDecoder {
    const val RGB_565: Int = 0
    const val ABGR_8888: Int = 1
    const val ARGB_8888: Int = 2

    private const val HEADER = 9

    class InvalidBitmapException(message: String) : Exception(message)

    fun decode(rootId: Long, compressed: ByteArray): InspectorScreenshot {
        val data = inflate(compressed)
        if (data.size < HEADER) throw InvalidBitmapException("헤더가 짧습니다: ${data.size}바이트")
        val width = leInt(data, 0)
        val height = leInt(data, 4)
        val format = data[8].toInt()
        if (width <= 0 || height <= 0) throw InvalidBitmapException("크기가 잘못됐습니다: ${width}x$height")
        val pixelSize = when (format) {
            RGB_565 -> 2
            ABGR_8888, ARGB_8888 -> 4
            else -> throw InvalidBitmapException("모르는 비트맵 형식: $format")
        }
        val count = width.toLong() * height
        if (data.size - HEADER < count * pixelSize) {
            throw InvalidBitmapException("픽셀이 모자랍니다: ${width}x$height 형식 $format, ${data.size - HEADER}바이트")
        }
        val argb = IntArray(count.toInt())
        var p = HEADER
        for (i in argb.indices) {
            argb[i] = when (format) {
                RGB_565 -> rgb565(data[p].toInt() and 0xff or ((data[p + 1].toInt() and 0xff) shl 8))
                ABGR_8888 -> argb(a = data[p + 3], r = data[p], g = data[p + 1], b = data[p + 2])
                else -> argb(a = data[p + 3], r = data[p + 2], g = data[p + 1], b = data[p])
            }
            p += pixelSize
        }
        return InspectorScreenshot(rootId, width, height, argb)
    }

    private fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream(compressed.size * 4)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw InvalidBitmapException("압축을 풀지 못했습니다: ${e.message}")
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw InvalidBitmapException("압축 데이터가 끝나지 않았습니다")
                }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun argb(a: Byte, r: Byte, g: Byte, b: Byte): Int =
        ((a.toInt() and 0xff) shl 24) or ((r.toInt() and 0xff) shl 16) or ((g.toInt() and 0xff) shl 8) or (b.toInt() and 0xff)

    /** 5·6·5비트를 8비트로 늘린다(위 비트를 아래로 복제). 불투명. */
    private fun rgb565(v: Int): Int {
        val r = (v shr 11) and 0x1f
        val g = (v shr 5) and 0x3f
        val b = v and 0x1f
        return (0xff shl 24) or (((r shl 3) or (r shr 2)) shl 16) or (((g shl 2) or (g shr 4)) shl 8) or ((b shl 3) or (b shr 2))
    }
}
