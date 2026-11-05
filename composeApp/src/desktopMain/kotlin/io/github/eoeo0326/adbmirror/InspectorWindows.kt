package io.github.eoeo0326.adbmirror

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.feature.inspector.InspectorViewModel
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/** Layout Inspector 창의 기본·최소 크기. */
val INSPECTOR_WINDOW_SIZE = DpSize(1280.dp, 800.dp)
val INSPECTOR_MIN_SIZE = DpSize(1000.dp, 640.dp)

/**
 * 기기 하나의 Layout Inspector 창. 미러링 창과 수명이 따로다(미러링 창을 닫아도 남는다).
 * ViewModel은 창 전용 [ViewModelStore]에 두어 [close]가 세션·연결과 viewModelScope까지 끝내게 한다.
 */
class InspectorWindowHolder(
    val device: Device,
    graph: AppGraph,
    val windowState: WindowState = WindowState(size = INSPECTOR_WINDOW_SIZE, position = WindowPosition.PlatformDefault),
) {
    private val store = ViewModelStore()
    val viewModel: InspectorViewModel = ViewModelProvider.create(store, viewModelFactory { initializer { graph.inspectorViewModel(device) } })[InspectorViewModel::class]

    /** 올릴 때마다 1씩 늘려 창을 앞으로 가져온다. */
    val focusRequest = mutableIntStateOf(0)

    private val closeStarted = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()

    /** inspector·에이전트 연결·forward·데몬·기기 설정을 정리한다. 창 닫기·앱 종료·종료 훅 어디서 불러도 한 번만 돈다. */
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
