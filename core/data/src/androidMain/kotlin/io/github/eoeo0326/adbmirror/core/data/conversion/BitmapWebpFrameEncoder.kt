package io.github.eoeo0326.adbmirror.core.data.conversion

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/** Android 내장 WebP 인코더(Bitmap.compress)로 프레임 한 장을 손실 WebP(RIFF 전체)로 만든다. */
object BitmapWebpFrameEncoder : WebpFrameEncoder {
    override fun encode(frame: RgbaFrame, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            bitmap.setHasAlpha(false) // 불투명 프레임: ALPH 청크를 만들지 않는다
            val q = quality.coerceIn(0, 100)
            val (format, level) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY to q
            } else {
                // Android 10 이하의 WEBP는 품질 100이면 무손실로 바뀌어 99까지만 쓴다.
                @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP to minOf(q, 99)
            }
            val out = ByteArrayOutputStream()
            check(bitmap.compress(format, level, out)) { "WebP로 압축하지 못했습니다" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
