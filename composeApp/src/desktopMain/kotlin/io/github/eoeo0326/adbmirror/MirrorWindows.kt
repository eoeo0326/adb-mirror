package io.github.eoeo0326.adbmirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import io.github.eoeo0326.adbmirror.feature.mirror.Connection
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorViewModel
import kotlinx.coroutines.CompletableDeferred
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 기기 하나의 미러링 창. ViewModel은 창 전용 [ViewModelStore]에 두어,
 * 창을 닫을 때 [close]가 viewModelScope까지 끝내게 한다.
 */
class MirrorWindowHolder(
    val device: Device,
    graph: AppGraph,
    /** 기기별로 기억한 위치에서 연다. 크기는 영상에 맞추므로 기억하지 않는다. */
    val windowState: WindowState = WindowState(size = DpSize(420.dp, 860.dp), position = WindowPosition.PlatformDefault),
) {
    private val store = ViewModelStore()
    val viewModel: MirrorViewModel = ViewModelProvider.create(store, viewModelFactory { initializer { graph.mirrorViewModel(device) } })[MirrorViewModel::class]

    /** 올릴 때마다 1씩 늘려 창을 앞으로 가져온다. */
    val focusRequest = mutableIntStateOf(0)

    private val closeStarted = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()

    /** 세션을 끝낸 뒤 viewModelScope까지 정리한다. 여러 곳(창 닫기·앱 종료·종료 훅)에서 불러도 한 번만 돈다. */
    suspend fun close() {
        if (!closeStarted.compareAndSet(false, true)) return closed.await()
        try {
            viewModel.shutdown()
            store.clear()
        } finally {
            closed.complete(Unit)
        }
    }
}

/** 영상 해상도가 바뀌면(첫 프레임·회전) 창 크기를 비율에 맞춘다. 화면의 85%를 넘지 않는다. */
@Composable
fun FrameWindowScope.FitWindowToVideo(holder: MirrorWindowHolder) {
    val state by holder.viewModel.state.collectAsState()
    val size = (state.connection as? Connection.Mirroring)?.videoSize
    LaunchedEffect(size) {
        size ?: return@LaunchedEffect
        holder.windowState.size = fittedWindowSize(size)
    }
    val focus by holder.focusRequest
    LaunchedEffect(focus) {
        if (focus > 0) {
            window.toFront()
            window.requestFocus()
        }
    }
}

private const val TOOLBAR_DP = 52
private const val CHROME_DP = 28 // 제목 표시줄 대략값

internal fun fittedWindowSize(video: VideoSize): DpSize {
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val maxW = screen.width * 0.85
    val maxH = screen.height * 0.85 - TOOLBAR_DP - CHROME_DP
    val scale = minOf(1.0, maxW / video.width, maxH / video.height)
    return DpSize((video.width * scale).dp, (video.height * scale + TOOLBAR_DP + CHROME_DP).dp)
}
