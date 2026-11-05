package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorEvent
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorProperty
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorScreenshot
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorSession
import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.GetComposablesResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.LayoutEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로세스 하나의 Layout Inspector 세션.
 *
 * - 수신기: View inspector 이벤트를 받아 루트 집합·루트별 layout을 저장하고, 스크린샷을 디코딩해 내보낸 뒤 갱신기에 신호만 준다.
 * - 갱신기: 신호를 [debounceMs]만큼 묶어 바뀐 루트의 Compose 트리를 받고(unchanged면 이전 것을 쓴다) 트리 전체를 내보낸다.
 *   Compose 응답을 기다리는 동안 이벤트 수신이 막히지 않도록 둘을 나눈다.
 * - 정리: 정상 종료([close])·오류·프로세스 종료·부모 취소 모두 [end] 한 곳을 거치며, NonCancellable로 한 번만 돈다.
 *
 * BITMAP 스크린샷은 실시간(continuous) fetch에서만 오므로 새로 고침도 continuous로 받고 곧바로 멈춘다.
 */
class InspectorSessionImpl(
    override val process: InspectableProcess,
    private val view: ViewInspection,
    compose: ComposeInspection?,
    override val composeVersion: String?,
    /** Compose 정보를 볼 수 없는 이유. 시작할 때 [InspectorEvent.ComposeUnavailable]로 한 번 알린다. */
    private val composeUnavailable: String? = null,
    private val isAlive: suspend () -> Boolean = { true },
    /** transport 연결이 끊기면 완료된다. */
    private val disconnected: Deferred<Throwable?>? = null,
    /** 정리 마지막에 부른다(App Inspection 클라이언트 닫기 등). */
    private val onClosed: suspend () -> Unit = {},
    parent: Job? = null,
    private val debounceMs: Long = 100,
    private val aliveCheckMs: Long = 2_000,
    private val decode: (rootId: Long, bytes: ByteArray) -> InspectorScreenshot = BitmapDecoder::decode,
) : InspectorSession {
    private val job = SupervisorJob(parent)
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val channel = Channel<InspectorEvent>(Channel.UNLIMITED)
    override val events: Flow<InspectorEvent> = channel.receiveAsFlow()

    @Volatile private var compose: ComposeInspection? = compose
    private val lock = Mutex()
    private var roots: List<Long> = emptyList()
    private val layouts = LinkedHashMap<Long, LayoutEvent>()
    private val composeTrees = HashMap<Long, GetComposablesResponse>()
    private val dirty = HashSet<Long>()
    private var forceFull = false
    private var generation = 0

    @Volatile private var live = true
    @Volatile private var showSystem = false
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** 다음 트리 방출을 기다리는 쪽([refresh]). */
    @Volatile private var treeWaiter: CompletableDeferred<Unit>? = null

    private val ended = AtomicBoolean(false)
    private val endDone = CompletableDeferred<Unit>()

    /** 수신·갱신·감시를 시작하고 첫 fetch를 보낸다. 실패하면 정리한 뒤 던진다. */
    suspend fun start() {
        // 부모가 취소돼도 정리가 돌도록 한다.
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                end()
            }
        }
        composeUnavailable?.let { channel.trySend(InspectorEvent.ComposeUnavailable(it)) }
        // fetch 응답 이벤트를 놓치지 않도록 수집기는 구독이 걸린 뒤에 다음으로 넘어간다(UNDISPATCHED).
        scope.launch(start = CoroutineStart.UNDISPATCHED) { view.events.collect(::onViewEvent) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { view.disposals.collect { message -> fail(if (isAliveSafe()) InspectorFailure.InspectorFailed else InspectorFailure.ProcessDied, message) } }
        compose?.let { c ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                c.disposals.collect { message ->
                    this@InspectorSessionImpl.compose = null
                    channel.trySend(InspectorEvent.ComposeUnavailable(message.ifEmpty { "Compose inspector가 닫혔습니다" }))
                    signal.trySend(Unit)
                }
            }
        }
        scope.launch { refresher() }
        scope.launch {
            while (true) {
                delay(aliveCheckMs)
                if (!isAliveSafe()) fail(InspectorFailure.ProcessDied, null)
            }
        }
        disconnected?.let { d -> scope.launch { fail(InspectorFailure.Disconnected, d.await()?.message) } }
        try {
            view.useBitmapScreenshots(1f)
            startFetch()
        } catch (e: Throwable) {
            end()
            throw e
        }
    }

    private suspend fun startFetch() {
        val response = view.startFetch(continuous = true)
        if (response.error.isNotEmpty()) channel.trySend(InspectorEvent.DeviceError("${response.code}: ${response.error}"))
    }

    private suspend fun onViewEvent(e: ViewLayoutInspection.Event) {
        when {
            e.hasRootsEvent() -> {
                lock.withLock {
                    roots = e.rootsEvent.idsList
                    val keep = roots.toSet()
                    layouts.keys.retainAll(keep)
                    composeTrees.keys.retainAll(keep)
                    dirty.retainAll(keep)
                }
                signal.trySend(Unit)
            }
            e.hasLayoutEvent() -> {
                val layout = e.layoutEvent
                val rootId = layout.rootView.node.id
                lock.withLock {
                    layouts[rootId] = layout
                    dirty += rootId
                    // roots 이벤트보다 layout이 먼저 오면 그 루트를 우선 넣어 둔다.
                    if (rootId !in roots) roots = roots + rootId
                }
                if (!layout.screenshot.bytes.isEmpty) {
                    runCatching { decode(rootId, layout.screenshot.bytes.toByteArray()) }
                        .onSuccess { channel.trySend(InspectorEvent.Screenshot(it)) }
                        .onFailure { channel.trySend(InspectorEvent.DeviceError("스크린샷을 읽지 못했습니다: ${it.message}")) }
                }
                signal.trySend(Unit)
            }
            e.hasErrorEvent() -> channel.trySend(InspectorEvent.DeviceError(e.errorEvent.message))
        }
    }

    private suspend fun refresher() {
        for (ignored in signal) {
            delay(debounceMs)
            val (order, targets, full) = lock.withLock {
                val full = forceFull
                forceFull = false
                val targets = if (full) layouts.keys.toList() else dirty.toList()
                dirty.clear()
                Triple(roots.filter { it in layouts }, targets, full)
            }
            val c = compose
            if (c != null) {
                for (rootId in targets) {
                    val response = runCatching {
                        c.composables(rootId, skipSystem = !showSystem, generation = ++generation, allowEmptyIfUnchanged = !full)
                    }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        channel.trySend(InspectorEvent.DeviceError("Compose 트리를 받지 못했습니다: ${e.message}"))
                        continue
                    }
                    val composables = response.getComposablesResponse
                    if (!composables.unchanged) lock.withLock { composeTrees[rootId] = composables }
                }
            }
            val (trees, waitingForLayouts) = lock.withLock {
                order.mapNotNull { id -> layouts[id]?.let { TreeMapper.map(it, if (compose != null) composeTrees[id] else null) } } to
                    (roots.isNotEmpty() && layouts.isEmpty())
            }
            // 창은 있는데 layout이 아직 없으면 빈 트리로 화면을 지우지 않는다.
            if (waitingForLayouts) continue
            channel.trySend(InspectorEvent.Tree(trees, composeAvailable = compose != null))
            if (lock.withLock { roots.isNotEmpty() && roots.all { it in layouts } }) treeWaiter?.complete(Unit)
        }
    }

    override suspend fun setLive(live: Boolean) {
        if (this.live == live) return
        this.live = live
        if (live) startFetch() else view.stopFetch()
    }

    override suspend fun refresh() {
        if (live) return
        val waiter = CompletableDeferred<Unit>()
        treeWaiter = waiter
        try {
            lock.withLock { forceFull = true }
            startFetch()
            withTimeoutOrNull(REFRESH_TIMEOUT_MS) { waiter.await() }
        } finally {
            treeWaiter = null
            if (!live) runCatching { view.stopFetch() }
        }
    }

    override suspend fun resetRecompositionCounts() {
        val c = compose ?: return
        c.applySettings()
        lock.withLock { forceFull = true }
        signal.trySend(Unit)
    }

    override suspend fun setShowSystemComposables(show: Boolean) {
        if (showSystem == show) return
        showSystem = show
        lock.withLock { forceFull = true }
        signal.trySend(Unit)
    }

    override suspend fun properties(node: LayoutNode): List<InspectorProperty> = when (node.kind) {
        LayoutNode.Kind.View -> PropertyFormatter.view(view.properties(node.rootId, node.id))
        LayoutNode.Kind.Composable -> {
            val c = compose ?: return emptyList()
            val response = c.parameters(node.rootId, node.id, node.anchorHash ?: 0, skipSystem = !showSystem, generation = generation)
            PropertyFormatter.compose(response.getParametersResponse)
        }
    }

    override suspend fun close() {
        end()
    }

    private suspend fun isAliveSafe(): Boolean = runCatching { isAlive() }.getOrDefault(true)

    private suspend fun fail(reason: InspectorFailure, detail: String?) {
        if (ended.get()) return
        channel.trySend(InspectorEvent.Failed(reason, detail?.takeIf { it.isNotEmpty() }))
        end()
    }

    /** 정리 순서: StopFetch → Compose·View 폐기(실패해도 계속) → 이벤트 닫기 → [onClosed]. 여러 번 불러도 한 번만 돈다. */
    private suspend fun end(): Unit = withContext(NonCancellable) {
        if (!ended.compareAndSet(false, true)) {
            endDone.await()
            return@withContext
        }
        try {
            runCatching { view.stopFetch() }
            runCatching { compose?.dispose() }
            runCatching { view.dispose() }
            runCatching { onClosed() }
        } finally {
            channel.close()
            job.cancel()
            endDone.complete(Unit)
        }
    }

    companion object {
        private const val REFRESH_TIMEOUT_MS = 5_000L
    }
}
