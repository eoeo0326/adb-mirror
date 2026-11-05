package io.github.eoeo0326.adbmirror.feature.inspector

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorConnection
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorEvent
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorException
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorScreenshot
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorSession
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.github.eoeo0326.adbmirror.core.domain.repository.InspectorRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage

/**
 * 기기 하나의 Layout Inspector 창. 연결 → 프로세스 목록 → (포그라운드 앱이면 자동) 세션 순으로 진행한다.
 * 화면 이미지는 State가 아니라 [screenshots]로 따로 내보내고, 변환은 [background]에서 한다.
 * 창을 닫을 때 홀더가 [shutdown]을 불러 세션·연결(transport·forward·데몬·기기 설정)을 정리한다.
 */
class InspectorViewModel(
    device: Device,
    private val repository: InspectorRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val toImage: (InspectorScreenshot) -> ImageBitmap = ::defaultImage,
) : ViewModel() {
    private val _state = MutableStateFlow(InspectorState(device))
    val state: StateFlow<InspectorState> = _state.asStateFlow()

    /** root id → 화면 이미지. 현재 트리에 없는 창의 이미지는 버린다. */
    private val _screenshots = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val screenshots: StateFlow<Map<Long, ImageBitmap>> = _screenshots.asStateFlow()

    private val _effects = Channel<InspectorEffect>(Channel.BUFFERED)
    val effects: Flow<InspectorEffect> = _effects.receiveAsFlow()

    private val lock = Mutex()
    @Volatile private var connection: InspectorConnection? = null
    @Volatile private var session: InspectorSession? = null
    private var sessionJob: Job? = null
    private var propertiesJob: Job? = null
    private var highlightJob: Job? = null
    private var shutDown = false

    init {
        connect()
    }

    fun onIntent(intent: InspectorIntent) {
        when (intent) {
            is InspectorIntent.SelectProcess -> select(intent.process)
            InspectorIntent.ReloadProcesses -> viewModelScope.launch { loadProcesses(autoSelect = false) }
            InspectorIntent.ToggleLive -> command {
                val live = !_state.value.live
                it.setLive(live)
                reduce(InspectorResult.LiveChanged(live))
            }
            InspectorIntent.Refresh -> command { it.refresh() }
            InspectorIntent.ResetCounts -> command {
                it.resetRecompositionCounts()
                reduce(InspectorResult.CountsReset)
            }
            InspectorIntent.ToggleSystem -> command {
                val show = !_state.value.showSystem
                it.setShowSystemComposables(show)
                reduce(InspectorResult.SystemChanged(show))
            }
            InspectorIntent.ToggleRecompositionColumns -> reduce(InspectorResult.RecompositionColumnsToggled)
            is InspectorIntent.Search -> reduce(InspectorResult.SearchChanged(intent.query))
            is InspectorIntent.SelectNode -> selectNode(intent.nodeId)
            is InspectorIntent.HoverNode -> if (_state.value.hoveredNodeId != intent.nodeId) reduce(InspectorResult.NodeHovered(intent.nodeId))
            is InspectorIntent.ToggleExpanded -> reduce(InspectorResult.ExpandToggled(intent.nodeId))
            InspectorIntent.Retry -> retry()
            InspectorIntent.OpenSettings -> _effects.trySend(InspectorEffect.OpenSettings)
        }
    }

    private fun connect() {
        viewModelScope.launch {
            reduce(InspectorResult.Step(InspectorStep.LocatingStudio))
            val c = try {
                withContext(background) { repository.connect(_state.value.device.serial) { reduce(InspectorResult.Step(it)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
                return@launch
            }
            if (!lock.withLock { if (shutDown) false else { connection = c; true } }) {
                withContext(NonCancellable) { c.close() }
                return@launch
            }
            reduce(InspectorResult.Connected(c.studioLabel))
            loadProcesses(autoSelect = true)
        }
    }

    private suspend fun loadProcesses(autoSelect: Boolean) {
        val c = connection ?: return
        val list = try {
            withContext(background) { c.processes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reduce(InspectorResult.DeviceError("프로세스 목록을 받지 못했습니다: ${e.message}"))
            emptyList()
        }
        reduce(InspectorResult.ProcessesLoaded(list))
        if (autoSelect && session == null) list.firstOrNull { it.foreground }?.let(::select)
    }

    private fun select(process: InspectableProcess) {
        val c = connection ?: return
        sessionJob?.cancel()
        propertiesJob?.cancel()
        _screenshots.value = emptyMap()
        reduce(InspectorResult.ProcessSelected(process))
        sessionJob = viewModelScope.launch {
            lock.withLock {
                session?.let { withContext(NonCancellable) { it.close() } }
                session = null
            }
            val s = try {
                withContext(background) { c.inspect(process) { reduce(InspectorResult.Step(it)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
                return@launch
            }
            val keep = lock.withLock { if (shutDown) false else { session = s; true } }
            if (!keep) {
                withContext(NonCancellable) { s.close() }
                return@launch
            }
            reduce(InspectorResult.SessionStarted(s.composeVersion))
            withContext(background) { s.events.collect(::onEvent) }
        }
    }

    private suspend fun onEvent(event: InspectorEvent) {
        when (event) {
            is InspectorEvent.Progress -> reduce(InspectorResult.Step(event.step))
            is InspectorEvent.Tree -> {
                val now = clock()
                reduce(InspectorResult.TreeReceived(event.roots, event.composeAvailable, now))
                val ids = event.roots.map { it.id }.toSet()
                _screenshots.update { shots -> shots.filterKeys { it in ids } }
                if (_state.value.recentlyRecomposed.isNotEmpty()) scheduleHighlightExpiry()
            }
            is InspectorEvent.Screenshot -> {
                val image = runCatching { toImage(event.shot) }.getOrNull() ?: return
                _screenshots.update { it + (event.shot.rootId to image) }
            }
            is InspectorEvent.ComposeUnavailable -> reduce(InspectorResult.ComposeUnavailable(event.reason))
            is InspectorEvent.DeviceError -> reduce(InspectorResult.DeviceError(event.message))
            is InspectorEvent.Failed -> {
                lock.withLock { session = null }
                _screenshots.value = emptyMap()
                if (event.reason == InspectorFailure.ProcessDied) {
                    reduce(InspectorResult.ProcessDied)
                    viewModelScope.launch { loadProcesses(autoSelect = false) }
                } else {
                    reduce(InspectorResult.Failed(event.reason, event.detail))
                }
            }
        }
    }

    private fun scheduleHighlightExpiry() {
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch {
            while (_state.value.recentlyRecomposed.isNotEmpty()) {
                delay(InspectorReducer.HIGHLIGHT_MS / 4)
                reduce(InspectorResult.HighlightExpired(clock()))
            }
        }
    }

    private fun selectNode(nodeId: Long?) {
        reduce(InspectorResult.NodeSelected(nodeId))
        propertiesJob?.cancel()
        val node = _state.value.selectedNode ?: return
        val s = session ?: return
        reduce(InspectorResult.PropertiesLoading(node.id))
        propertiesJob = viewModelScope.launch {
            try {
                val properties = withContext(background) { s.properties(node) }
                reduce(InspectorResult.PropertiesLoaded(node.id, properties))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                reduce(InspectorResult.PropertiesFailed(node.id, e.message ?: e.toString()))
            }
        }
    }

    private fun command(block: suspend (InspectorSession) -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                withContext(background) { block(s) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                reduce(InspectorResult.DeviceError(e.message ?: e.toString()))
            }
        }
    }

    /** 실패한 단계부터 다시 한다: 연결이 없으면 연결부터, 있으면 프로세스 목록부터. */
    private fun retry() {
        if (connection == null) {
            _state.update { it.copy(phase = InspectorPhase.Preparing(null)) }
            connect()
        } else {
            _state.update { it.copy(phase = InspectorPhase.ChoosingProcess) }
            viewModelScope.launch { loadProcesses(autoSelect = false) }
        }
    }

    private fun fail(e: Throwable) {
        val failure = (e as? InspectorException)?.failure ?: InspectorFailure.InspectorFailed
        reduce(InspectorResult.Failed(failure, e.message))
    }

    /** 세션 → 연결 순으로 정리한다. 여러 번 불러도 된다. 정리 중 새 연결·세션이 생기면 바로 닫는다. */
    suspend fun shutdown() = withContext(NonCancellable) {
        val (s, c) = lock.withLock {
            shutDown = true
            val pair = session to connection
            session = null
            connection = null
            pair
        }
        sessionJob?.cancel()
        runCatching { s?.close() }
        runCatching { c?.close() }
        _screenshots.value = emptyMap()
    }

    private fun reduce(result: InspectorResult) = _state.update { InspectorReducer.reduce(it, result) }

    companion object {
        /** ARGB 픽셀을 Skia 이미지로. */
        fun defaultImage(shot: InspectorScreenshot): ImageBitmap {
            val image = BufferedImage(shot.width, shot.height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, shot.width, shot.height, shot.argb, 0, shot.width)
            return image.toComposeImageBitmap()
        }
    }
}
