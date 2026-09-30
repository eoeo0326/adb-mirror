package io.github.eoeo0326.adbmirror.feature.mirror.video

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/**
 * YUV 평면을 Skia 이미지로 올려 SkSL 셰이더에서 RGB로 바꿔 그린다. CPU 색 변환(swscale)과 BGRA 복사를 없애고,
 * GPU로 올리는 양도 픽셀당 4바이트에서 1.5바이트로 준다.
 */
internal object YuvRenderer {
    private val effect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(SKSL) }

    // U·V는 절반 해상도라 p * 0.5에서 읽는다. GRAY_8 이미지는 (g, g, g, 1)로 읽힌다.
    private const val SKSL = """
uniform shader yPlane;
uniform shader uPlane;
uniform shader vPlane;
uniform float4 range;
uniform float4 coef;
half4 main(float2 p) {
    float yv = yPlane.eval(p).r;
    float2 c = p * 0.5;
    float cb = uPlane.eval(c).r;
    float cr = vPlane.eval(c).r;
    float y = (yv - range.x) * range.y;
    float u = (cb - range.z) * range.w;
    float v = (cr - range.z) * range.w;
    return half4(half3(saturate(y + coef.x * v), saturate(y - coef.y * u - coef.z * v), saturate(y + coef.w * u)), 1);
}
"""

    /** 올린 평면 이미지 한 벌. 다음 프레임이 오면 닫는다. */
    class Planes(val width: Int, val height: Int, val y: Image, val u: Image, val v: Image, val bt709: Boolean, val fullRange: Boolean) : AutoCloseable {
        override fun close() {
            y.close()
            u.close()
            v.close()
        }
    }

    fun upload(f: FfmpegH264Decoder.YuvFrame): Planes {
        val cw = (f.width + 1) / 2
        val ch = (f.height + 1) / 2
        val y = Image.makeRaster(ImageInfo(f.width, f.height, ColorType.GRAY_8, ColorAlphaType.OPAQUE), f.y, f.yStride)
        val u = Image.makeRaster(ImageInfo(cw, ch, ColorType.GRAY_8, ColorAlphaType.OPAQUE), f.u, f.cStride)
        val v = Image.makeRaster(ImageInfo(cw, ch, ColorType.GRAY_8, ColorAlphaType.OPAQUE), f.v, f.cStride)
        return Planes(f.width, f.height, y, u, v, f.bt709, f.fullRange)
    }

    /** [planes]를 [dst] 사각형에 늘려 그린다. */
    fun draw(canvas: Canvas, planes: Planes, dst: Rect) {
        val sampling = SamplingMode.LINEAR
        val ys = planes.y.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling)
        val us = planes.u.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling)
        val vs = planes.v.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling)
        val builder = RuntimeShaderBuilder(effect)
        builder.child("yPlane", ys)
        builder.child("uPlane", us)
        builder.child("vPlane", vs)
        if (planes.fullRange) builder.uniform("range", 0f, 1f, 128f / 255f, 1f)
        else builder.uniform("range", 16f / 255f, 255f / 219f, 128f / 255f, 255f / 224f)
        if (planes.bt709) builder.uniform("coef", 1.5748f, 0.187324f, 0.468124f, 1.8556f)
        else builder.uniform("coef", 1.402f, 0.344136f, 0.714136f, 1.772f)
        // 셰이더 좌표를 영상 픽셀 좌표로: 화면 사각형 → 영상 크기
        val local = Matrix33.makeTranslate(dst.left, dst.top).makeConcat(Matrix33.makeScale(dst.width / planes.width, dst.height / planes.height))
        val shader = builder.makeShader(local)
        Paint().use { paint ->
            paint.shader = shader
            canvas.drawRect(dst, paint)
        }
        shader.close()
        builder.close()
        ys.close()
        us.close()
        vs.close()
    }

    /** `ADB_MIRROR_GPU_YUV=0`이면 이 경로를 쓰지 않고 BGRA로 그린다(비교·문제 확인용). */
    fun enabled(): Boolean = System.getenv("ADB_MIRROR_GPU_YUV") != "0"
}
