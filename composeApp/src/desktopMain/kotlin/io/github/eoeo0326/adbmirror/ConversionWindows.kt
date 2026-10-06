package io.github.eoeo0326.adbmirror

import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.eoeo0326.adbmirror.feature.conversion.ConversionIntent
import io.github.eoeo0326.adbmirror.feature.conversion.ConversionViewModel
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 녹화 하나의 변환 창. 미러링 창과 다른 [ViewModelStore]를 가져, 미러링 창을 닫아도 변환이 이어진다.
 * 창을 닫으면 [close]가 진행 중인 변환을 취소하고 임시 파일 정리까지 기다린다.
 */
class ConversionWindowHolder(val files: List<String>, graph: AppGraph) {
    private val store = ViewModelStore()
    val viewModel: ConversionViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { graph.conversionViewModel() } })[ConversionViewModel::class]

    /** 창 제목에 쓸 첫 녹화 파일 이름 */
    val title: String = java.io.File(files.first()).name

    /** 올릴 때마다 1씩 늘려 창을 앞으로 가져온다. */
    val focusRequest = mutableIntStateOf(0)

    private val closeStarted = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()

    init {
        viewModel.onIntent(ConversionIntent.Open(files))
    }

    /** 여러 곳(창 닫기·앱 종료·종료 훅)에서 불러도 한 번만 돈다. */
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
