package io.github.eoeo0326.adbmirror.feature.mirror.video

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/** 클릭 지점 파문 하나. 좌표는 뷰 픽셀이다. */
data class Ripple(val x: Float, val y: Float, val ageMs: Long = 0)

/**
 * 클릭 이펙트 상태. 누른 곳에 파문을 남기고, 누른 채 움직이면 경로를 그린다.
 * 손을 뗀 경로는 [PATH_FADE_MS] 동안 흐려지다 사라진다. 시간은 [advance]로만 흐른다.
 */
data class TouchTrail(
    val ripples: List<Ripple> = emptyList(),
    val path: List<Offset> = emptyList(),
    val pressed: Boolean = false,
    /** 손을 뗀 뒤 지난 시간. 누르고 있는 동안은 0. */
    val pathFadeMs: Long = 0,
) {
    val isIdle: Boolean get() = ripples.isEmpty() && path.isEmpty()

    fun press(x: Float, y: Float) = copy(ripples = ripples + Ripple(x, y), path = listOf(Offset(x, y)), pressed = true, pathFadeMs = 0)

    fun move(x: Float, y: Float) = if (!pressed) this else copy(path = (path + Offset(x, y)).takeLast(MAX_PATH_POINTS))

    fun release() = if (!pressed) this else copy(pressed = false, pathFadeMs = 0)

    fun advance(dtMs: Long): TouchTrail {
        val aged = ripples.map { it.copy(ageMs = it.ageMs + dtMs) }.filter { it.ageMs < RIPPLE_MS }
        if (pressed) return copy(ripples = aged)
        val fade = pathFadeMs + dtMs
        return if (fade >= PATH_FADE_MS) copy(ripples = aged, path = emptyList(), pathFadeMs = 0) else copy(ripples = aged, pathFadeMs = fade)
    }

    companion object {
        const val RIPPLE_MS = 400L
        const val PATH_FADE_MS = 300L
        const val MAX_PATH_POINTS = 256
    }
}

private val EffectColor = Color(0xFF2FB38A)

/**
 * 영상 위에 클릭 이펙트를 그린다. 자식보다 먼저(Initial) 포인터를 보기만 하고 소비하지 않으므로
 * 자식의 터치 전달에는 영향이 없다. 레터박스를 누른 것은 기기로 가지 않으니 그리지 않는다.
 */
fun Modifier.touchEffect(enabled: Boolean, videoSize: VideoSize?): Modifier = composed {
    var trail by remember { mutableStateOf(TouchTrail()) }
    val on by rememberUpdatedState(enabled)
    LaunchedEffect(enabled) { if (!enabled) trail = TouchTrail() }

    LaunchedEffect(trail.isIdle) {
        if (trail.isIdle) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (!trail.isIdle) {
            withFrameMillis { now ->
                trail = trail.advance(now - last)
                last = now
            }
        }
    }

    this
        .pointerInput(videoSize) {
            val video = videoSize ?: return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!on) continue
                    val p = event.changes.firstOrNull()?.position ?: continue
                    val r = fitRect(size.width.toFloat(), size.height.toFloat(), video)
                    val cx = p.x.coerceIn(r.left, r.left + r.width)
                    val cy = p.y.coerceIn(r.top, r.top + r.height)
                    trail = when (event.type) {
                        PointerEventType.Press -> if (cx == p.x && cy == p.y) trail.press(p.x, p.y) else trail
                        PointerEventType.Move -> trail.move(cx, cy)
                        PointerEventType.Release -> trail.release()
                        else -> trail
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            val t = trail
            if (t.path.size > 1) {
                val alpha = 0.7f * (1f - t.pathFadeMs.toFloat() / TouchTrail.PATH_FADE_MS)
                val path = Path().apply {
                    moveTo(t.path[0].x, t.path[0].y)
                    for (i in 1 until t.path.size) lineTo(t.path[i].x, t.path[i].y)
                }
                drawPath(path, EffectColor, alpha = alpha, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            for (r in t.ripples) {
                val f = r.ageMs.toFloat() / TouchTrail.RIPPLE_MS
                val center = Offset(r.x, r.y)
                drawCircle(EffectColor, radius = (8f + 20f * f).dp.toPx(), center = center, alpha = 0.5f * (1f - f), style = Stroke(width = 3.dp.toPx()))
                drawCircle(EffectColor, radius = 6.dp.toPx(), center = center, alpha = 0.6f * (1f - f))
            }
        }
}
