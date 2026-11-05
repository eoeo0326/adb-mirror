package io.github.eoeo0326.adbmirror.feature.inspector

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModelStore
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorConnection
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorEvent
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorException
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorProperty
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorScreenshot
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorSession
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import io.github.eoeo0326.adbmirror.core.domain.repository.InspectorRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InspectorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val device = Device("S1", DeviceState.Online, "Pixel")
    private val app = InspectableProcess(10, "com.example", "com.example", "arm64", foreground = true)
    private val other = InspectableProcess(20, "com.other", "com.other", "arm64")
    private val log = mutableListOf<String>()

    private inner class FakeSession(override val process: InspectableProcess) : InspectorSession {
        val channel = Channel<InspectorEvent>(Channel.UNLIMITED)
        override val events: Flow<InspectorEvent> = channel.receiveAsFlow()
        override val composeVersion: String? = "1.7.8"
        override suspend fun setLive(live: Boolean) { log += "live($live)" }
        override suspend fun refresh() { log += "refresh" }
        override suspend fun resetRecompositionCounts() { log += "reset" }
        override suspend fun setShowSystemComposables(show: Boolean) { log += "system($show)" }
        override suspend fun properties(node: LayoutNode): List<InspectorProperty> = listOf(InspectorProperty("Parameters", "text", "hi ${node.id}"))
        override suspend fun close() { log += "session.close(${process.pid})"; channel.close() }
    }

    private inner class FakeConnection(var processList: List<InspectableProcess> = listOf(other, app)) : InspectorConnection {
        override val serial = "S1"
        override val studioLabel = "Android Studio 2026.2.1"
        val sessions = mutableListOf<FakeSession>()
        var inspectFailure: Throwable? = null
        override suspend fun processes() = processList
        override suspend fun inspect(process: InspectableProcess, onStep: (InspectorStep) -> Unit): InspectorSession {
            onStep(InspectorStep.Attaching)
            inspectFailure?.let { throw it }
            return FakeSession(process).also { sessions += it }
        }
        override suspend fun close() { log += "connection.close" }
    }

    private inner class FakeRepository(val connection: FakeConnection = FakeConnection()) : InspectorRepository {
        val failures = ArrayDeque<Throwable>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun connect(serial: String, onStep: (InspectorStep) -> Unit): InspectorConnection {
            onStep(InspectorStep.PushingFiles)
            gate?.await()
            failures.removeFirstOrNull()?.let { throw it }
            return connection
        }
    }

    private var now = 1_000L
    private val store = ViewModelStore()

    private fun viewModel(repo: InspectorRepository) = InspectorViewModel(
        device, repo, clock = { now }, background = dispatcher, toImage = { ImageBitmap(it.width, it.height) },
    ).also { vm -> store.put("vm", vm) }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun connectsAndAutoSelectsForegroundProcess() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)
        val state = vm.state.value
        assertEquals(InspectorPhase.Inspecting, state.phase)
        assertEquals("Android Studio 2026.2.1", state.studioLabel)
        assertEquals(listOf(other, app), state.processes)
        assertEquals(app, state.selectedProcess)
        assertEquals("1.7.8", state.composeVersion)

        val session = repo.connection.sessions.single()
        session.channel.send(InspectorEvent.Tree(listOf(node(1, children = arrayOf(node(2)))), composeAvailable = true))
        session.channel.send(InspectorEvent.Screenshot(InspectorScreenshot(1, 2, 3, IntArray(6))))
        assertEquals(listOf(1L), vm.state.value.roots.map { it.id })
        assertEquals(1_000L, vm.state.value.lastUpdatedMs)
        assertEquals(setOf(1L), vm.screenshots.value.keys)
        assertEquals(3, vm.screenshots.value.getValue(1).height)

        // 창이 사라지면 그 화면 이미지도 버린다.
        session.channel.send(InspectorEvent.Tree(listOf(node(5)), composeAvailable = true))
        assertEquals(emptySet(), vm.screenshots.value.keys)
    }

    @Test
    fun waitsForChoiceWhenNoForegroundProcess() = runTest(dispatcher) {
        val repo = FakeRepository(FakeConnection(listOf(other)))
        val vm = viewModel(repo)
        assertEquals(InspectorPhase.ChoosingProcess, vm.state.value.phase)
        assertTrue(repo.connection.sessions.isEmpty())
        vm.onIntent(InspectorIntent.SelectProcess(other))
        assertEquals(InspectorPhase.Inspecting, vm.state.value.phase)
        assertEquals(20, repo.connection.sessions.single().process.pid)
    }

    @Test
    fun studioNotFoundThenRetryConnects() = runTest(dispatcher) {
        val repo = FakeRepository().apply { failures += InspectorException(InspectorFailure.StudioNotFound, "없음") }
        val vm = viewModel(repo)
        assertEquals(InspectorPhase.Failed(InspectorFailure.StudioNotFound, "없음"), vm.state.value.phase)
        vm.onIntent(InspectorIntent.Retry)
        assertEquals(InspectorPhase.Inspecting, vm.state.value.phase)
    }

    @Test
    fun attachFailureShowsReasonAndRetryReturnsToProcessList() = runTest(dispatcher) {
        val connection = FakeConnection().apply { inspectFailure = InspectorException(InspectorFailure.AttachFailed, "시간 초과") }
        val vm = viewModel(FakeRepository(connection))
        assertEquals(InspectorPhase.Failed(InspectorFailure.AttachFailed, "시간 초과"), vm.state.value.phase)
        vm.onIntent(InspectorIntent.Retry)
        assertEquals(InspectorPhase.ChoosingProcess, vm.state.value.phase)
    }

    @Test
    fun processDeathReturnsToReloadedList() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)
        repo.connection.processList = listOf(other)
        repo.connection.sessions.single().channel.send(InspectorEvent.Failed(InspectorFailure.ProcessDied))
        assertEquals(InspectorPhase.ChoosingProcess, vm.state.value.phase)
        assertEquals("앱이 종료됐습니다", vm.state.value.processNotice)
        assertEquals(listOf(other), vm.state.value.processes)
    }

    @Test
    fun selectingNodeLoadsPropertiesAndCommandsReachSession() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)
        repo.connection.sessions.single().channel.send(InspectorEvent.Tree(listOf(node(1, children = arrayOf(node(2)))), true))
        vm.onIntent(InspectorIntent.SelectNode(2))
        assertEquals(PropertiesState.Loaded(2, listOf(InspectorProperty("Parameters", "text", "hi 2"))), vm.state.value.properties)

        vm.onIntent(InspectorIntent.ToggleLive)
        vm.onIntent(InspectorIntent.Refresh)
        vm.onIntent(InspectorIntent.ResetCounts)
        vm.onIntent(InspectorIntent.ToggleSystem)
        assertEquals(listOf("live(false)", "refresh", "reset", "system(true)"), log)
        assertEquals(false, vm.state.value.live)
        assertEquals(true, vm.state.value.showSystem)
    }

    @Test
    fun recompositionHighlightExpires() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = viewModel(repo)
        val session = repo.connection.sessions.single()
        session.channel.send(InspectorEvent.Tree(listOf(node(1, recomp = 1)), true))
        now = 1_100
        session.channel.send(InspectorEvent.Tree(listOf(node(1, recomp = 2)), true))
        assertEquals(setOf(1L), vm.state.value.recentlyRecomposed.keys)
        now = 2_200
        testScheduler.advanceTimeBy(InspectorReducer.HIGHLIGHT_MS)
        assertEquals(emptyMap(), vm.state.value.recentlyRecomposed)
    }

    @Test
    fun shutdownClosesSessionThenConnectionOnce() = runTest(dispatcher) {
        val vm = viewModel(FakeRepository())
        vm.shutdown()
        vm.shutdown()
        assertEquals(listOf("session.close(10)", "connection.close"), log)
    }

    @Test
    fun connectionArrivingAfterShutdownIsClosed() = runTest(dispatcher) {
        val repo = FakeRepository().apply { gate = CompletableDeferred() }
        val vm = viewModel(repo)
        vm.shutdown()
        repo.gate!!.complete(Unit)
        assertEquals(listOf("connection.close"), log)
        assertTrue(repo.connection.sessions.isEmpty())
    }
}
