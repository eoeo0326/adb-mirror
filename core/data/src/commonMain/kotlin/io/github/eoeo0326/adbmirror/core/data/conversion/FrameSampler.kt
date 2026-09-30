package io.github.eoeo0326.adbmirror.core.data.conversion

/**
 * 가변 프레임레이트(화면이 멈추면 프레임이 안 옴) 영상을 일정한 fps로 뽑는다.
 * 출력 시각 t = start + k/fps마다 그 시각까지 나온 마지막 프레임을 쓰고, 같은 프레임이 이어지면 한 장으로 합쳐 길이를 늘린다.
 *
 * [offer]는 PTS 순서로 부르고, 나오는 (프레임, 길이ms)를 차례로 인코더에 넣는다. [finish]로 끝까지 채운다.
 * 첫 출력 시각보다 늦게 첫 프레임이 오면 그 앞 시각은 건너뛴다.
 */
class FrameSampler<T : Any>(private val startMs: Long, private val endMs: Long, private val fps: Int) {
    init {
        require(endMs > startMs && fps > 0)
    }

    private var tick = 0L
    private var held: T? = null
    private var pending: T? = null
    private var pendingStartTick = 0L

    private fun timeOf(k: Long) = startMs + k * 1000 / fps

    /** 지금까지 채운 출력 시각의 비율(0~1). */
    val progress: Float get() = ((timeOf(tick) - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)

    /** 끝 시각을 넘은 프레임이 왔으면 true. 그 뒤로는 디코딩을 멈춰도 된다. */
    var done = false
        private set

    fun offer(ptsMs: Long, frame: T): List<Pair<T, Int>> {
        val out = mutableListOf<Pair<T, Int>>()
        advanceTo(ptsMs, out)
        if (ptsMs >= endMs) done = true else held = frame
        return out
    }

    fun finish(): List<Pair<T, Int>> {
        val out = mutableListOf<Pair<T, Int>>()
        advanceTo(endMs, out)
        flush(out)
        return out
    }

    /** [untilMs]보다 앞선 출력 시각을 모두 지금 들고 있는 프레임으로 채운다. */
    private fun advanceTo(untilMs: Long, out: MutableList<Pair<T, Int>>) {
        while (timeOf(tick) < untilMs && timeOf(tick) < endMs) {
            val frame = held
            if (frame != null) {
                if (pending !== frame) {
                    flush(out)
                    pending = frame
                    pendingStartTick = tick
                }
            }
            tick++
        }
    }

    private fun flush(out: MutableList<Pair<T, Int>>) {
        val p = pending ?: return
        val duration = (minOf(timeOf(tick), endMs) - timeOf(pendingStartTick)).toInt()
        if (duration > 0) out += p to duration
        pending = null
    }
}
