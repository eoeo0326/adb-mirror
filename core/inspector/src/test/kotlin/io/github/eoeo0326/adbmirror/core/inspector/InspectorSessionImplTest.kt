package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorEvent
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorScreenshot
import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import io.github.eoeo0326.adbmirror.core.domain.model.NodeBounds
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectorSessionImplTest {
    private val process = InspectableProcess(1, "com.example", "com.example", "arm64", foreground = true)

    private class FakeView(val log: MutableList<String>) : ViewInspection {
        override val events = MutableSharedFlow<ViewLayoutInspection.Event>(extraBufferCapacity = 64)
        override val disposals = MutableSharedFlow<String>(extraBufferCapacity = 4)
        var onStartFetch: suspend (Boolean) -> Unit = {}
        override suspend fun useBitmapScreenshots(scale: Float) { log += "bitmap" }
        override suspend fun startFetch(continuous: Boolean): ViewLayoutInspection.StartFetchResponse {
            log += "start($continuous)"
            onStartFetch(continuous)
            return ViewLayoutInspection.StartFetchResponse.getDefaultInstance()
        }
        override suspend fun stopFetch() { log += "stop" }
        override suspend fun properties(rootViewId: Long, viewId: Long): ViewLayoutInspection.GetPropertiesResponse {
            log += "props($rootViewId,$viewId)"
            return ViewLayoutInspection.GetPropertiesResponse.getDefaultInstance()
        }
        override suspend fun dispose() {
            log += "view.dispose"
            error("이미 폐기됨")
        }
    }

    private class FakeCompose(val log: MutableList<String>) : ComposeInspection {
        override val disposals = MutableSharedFlow<String>(extraBufferCapacity = 4)
        var unchanged = false
        var unknownSettings = false
        override suspend fun updateSettings(includeRecomposeCounts: Boolean, keepRecomposeCounts: Boolean, delayParameterExtractions: Boolean, reduceChildNesting: Boolean): ComposeLayoutInspection.Response {
            log += "settings(keep=$keepRecomposeCounts,reduce=$reduceChildNesting)"
            return ComposeLayoutInspection.Response.newBuilder().apply {
                if (unknownSettings && reduceChildNesting) setUnknownCommandResponse(ComposeLayoutInspection.UnknownCommandResponse.getDefaultInstance())
            }.build()
        }
        override suspend fun composables(rootViewId: Long, skipSystem: Boolean, generation: Int, allowEmptyIfUnchanged: Boolean): ComposeLayoutInspection.Response {
            log += "composables($rootViewId,skipSystem=$skipSystem,allowEmpty=$allowEmptyIfUnchanged)"
            val response = if (unchanged && allowEmptyIfUnchanged) {
                ComposeLayoutInspection.GetComposablesResponse.newBuilder().setUnchanged(true).build()
            } else {
                ComposeLayoutInspection.GetComposablesResponse.newBuilder()
                    .addStrings(ComposeLayoutInspection.StringEntry.newBuilder().setId(1).setStr("Text"))
                    .addRoots(
                        ComposeLayoutInspection.ComposableRoot.newBuilder().setViewId(rootViewId).addNodes(
                            ComposeLayoutInspection.ComposableNode.newBuilder().setId(-generation.toLong()).setName(1).setRecomposeCount(generation),
                        ),
                    ).build()
            }
            return ComposeLayoutInspection.Response.newBuilder().setGetComposablesResponse(response).build()
        }
        override suspend fun parameters(rootViewId: Long, composableId: Long, anchorHash: Int, skipSystem: Boolean, generation: Int): ComposeLayoutInspection.Response {
            log += "params($rootViewId,$composableId,$anchorHash)"
            return ComposeLayoutInspection.Response.getDefaultInstance()
        }
        override suspend fun dispose() { log += "compose.dispose" }
    }

    private val log = CopyOnWriteArrayList<String>()
    private val view = FakeView(log)
    private val compose = FakeCompose(log)

    private fun session(
        compose: ComposeInspection? = this.compose,
        isAlive: suspend () -> Boolean = { true },
        parent: Job? = null,
        disconnected: CompletableDeferred<Throwable?>? = null,
    ) = InspectorSessionImpl(
        process, view, compose, composeVersion = "1.7.8", composeUnavailable = if (compose == null) "없음" else null,
        isAlive = isAlive, disconnected = disconnected, onClosed = { log += "closed" }, parent = parent,
        debounceMs = 10, aliveCheckMs = 50,
        decode = { root, bytes -> InspectorScreenshot(root, 1, 1, intArrayOf(bytes.size)) },
    )

    private fun layout(rootId: Long, withScreenshot: Boolean = true) = ViewLayoutInspection.Event.newBuilder().setLayoutEvent(
        ViewLayoutInspection.LayoutEvent.newBuilder()
            .addStrings(ViewLayoutInspection.StringEntry.newBuilder().setId(1).setStr("DecorView"))
            .setRootView(ViewLayoutInspection.RootView.newBuilder().setNode(ViewLayoutInspection.ViewNode.newBuilder().setId(rootId).setClassName(1)))
            .apply { if (withScreenshot) setScreenshot(ViewLayoutInspection.Screenshot.newBuilder().setType(ViewLayoutInspection.Screenshot.Type.BITMAP).setBytes(com.google.protobuf.ByteString.copyFrom(byteArrayOf(1, 2, 3)))) },
    ).build()

    private fun roots(vararg ids: Long) = ViewLayoutInspection.Event.newBuilder().setRootsEvent(ViewLayoutInspection.WindowRootsEvent.newBuilder().addAllIds(ids.toList())).build()

    @Test
    fun emitsScreenshotAndMergedTreeAfterLiveFetch() = runBlocking {
        val s = session()
        view.onStartFetch = {
            view.events.emit(roots(10))
            view.events.emit(layout(10))
        }
        val tree = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { s.events.filterIsInstance<InspectorEvent.Tree>().first() } }
        s.start()
        val t = tree.await()
        assertTrue(t.composeAvailable)
        val root = t.roots.single()
        assertEquals("DecorView", root.name)
        assertEquals("Text", root.children.single().name)
        assertEquals(listOf("bitmap", "start(true)"), log.take(2))
        assertTrue(log.contains("composables(10,skipSystem=true,allowEmpty=true)"))
        s.close()
    }

    @Test
    fun debouncesBurstOfLayoutEventsAndReusesUnchangedComposeTree() = runBlocking {
        val s = session()
        val trees = async(start = CoroutineStart.UNDISPATCHED) { s.events.filterIsInstance<InspectorEvent.Tree>().toList() }
        s.start()
        view.events.emit(roots(10))
        view.events.emit(layout(10))
        withTimeout(5_000) { while (log.none { it.startsWith("composables") }) kotlinx.coroutines.delay(5) }
        kotlinx.coroutines.delay(100)
        compose.unchanged = true
        val composablesBefore = log.count { it.startsWith("composables") }
        repeat(5) { view.events.emit(layout(10, withScreenshot = false)) }
        kotlinx.coroutines.delay(200)
        s.close()
        val all = trees.await()
        val burstRequests = log.count { it.startsWith("composables") } - composablesBefore
        assertTrue(burstRequests in 1..2, "5번의 layout 이벤트는 디바운스로 묶인다: $burstRequests")
        assertTrue(all.none { it.roots.isEmpty() }, "layout을 기다리는 동안 빈 트리를 내보내지 않는다")
        // unchanged면 이전 Compose 트리를 그대로 붙인다.
        assertEquals("Text", all.last().roots.single().children.single().name)
    }

    @Test
    fun rootsEventDropsWindowsThatWentAway() = runBlocking {
        val s = session(compose = null)
        val trees = async(start = CoroutineStart.UNDISPATCHED) { s.events.filterIsInstance<InspectorEvent.Tree>().toList() }
        s.start()
        view.events.emit(roots(10, 20))
        view.events.emit(layout(10))
        view.events.emit(layout(20))
        kotlinx.coroutines.delay(100)
        view.events.emit(roots(20))
        kotlinx.coroutines.delay(100)
        s.close()
        val all = trees.await()
        assertEquals(listOf(10L, 20L), all.first { it.roots.size == 2 }.roots.map { it.id })
        assertEquals(listOf(20L), all.last().roots.map { it.id })
        assertTrue(all.all { !it.composeAvailable })
    }

    @Test
    fun closeCleansUpInOrderOnceEvenIfDisposeFails() = runBlocking {
        val s = session()
        s.start()
        s.close()
        s.close()
        assertEquals(listOf("stop", "compose.dispose", "view.dispose", "closed"), log.dropWhile { it != "stop" })
        assertEquals(emptyList(), s.events.toList(), "닫으면 이벤트 흐름도 끝난다")
    }

    @Test
    fun parentCancellationRunsCleanup() = runBlocking {
        val parent = Job()
        val s = session(parent = parent)
        s.start()
        parent.cancel()
        withTimeout(5_000) { while ("closed" !in log) kotlinx.coroutines.delay(5) }
        assertEquals(1, log.count { it == "closed" })
        assertEquals(1, log.count { it == "view.dispose" })
    }

    @Test
    fun processDeathFailsAndCleansUp() = runBlocking {
        var alive = true
        val s = session(isAlive = { alive })
        val failed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { s.events.filterIsInstance<InspectorEvent.Failed>().first() } }
        s.start()
        alive = false
        assertEquals(InspectorFailure.ProcessDied, failed.await().reason)
        withTimeout(5_000) { while ("closed" !in log) kotlinx.coroutines.delay(5) }
        assertEquals(1, log.count { it == "closed" })
    }

    @Test
    fun viewDisposedWhileAliveIsInspectorFailure() = runBlocking {
        val s = session()
        val failed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { s.events.filterIsInstance<InspectorEvent.Failed>().first() } }
        s.start()
        view.disposals.emit("replaced by another client")
        val f = failed.await()
        assertEquals(InspectorFailure.InspectorFailed, f.reason)
        assertEquals("replaced by another client", f.detail)
    }

    @Test
    fun disconnectFails() = runBlocking {
        val lost = CompletableDeferred<Throwable?>()
        val s = session(disconnected = lost)
        val failed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { s.events.filterIsInstance<InspectorEvent.Failed>().first() } }
        s.start()
        lost.complete(RuntimeException("network closed"))
        assertEquals(InspectorFailure.Disconnected, failed.await().reason)
    }

    @Test
    fun composeDisposalFallsBackToViewOnly() = runBlocking {
        val s = session()
        val notice = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { s.events.filterIsInstance<InspectorEvent.ComposeUnavailable>().first() } }
        s.start()
        compose.disposals.emit("APP_PROGUARDED")
        assertEquals("APP_PROGUARDED", notice.await().reason)
        val tree = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { s.events.filterIsInstance<InspectorEvent.Tree>().first() } }
        view.events.emit(layout(10))
        assertTrue(!tree.await().composeAvailable)
        s.close()
    }

    @Test
    fun resetSystemToggleAndRefreshRequestFullTrees() = runBlocking {
        val s = session()
        s.start()
        view.events.emit(roots(10))
        view.events.emit(layout(10))
        withTimeout(5_000) { while (log.none { it.startsWith("composables") }) kotlinx.coroutines.delay(5) }
        log.clear()
        s.resetRecompositionCounts()
        withTimeout(5_000) { while (log.none { it.startsWith("composables") }) kotlinx.coroutines.delay(5) }
        assertEquals("settings(keep=false,reduce=true)", log.first())
        assertTrue("composables(10,skipSystem=true,allowEmpty=false)" in log)

        log.clear()
        s.setShowSystemComposables(true)
        withTimeout(5_000) { while (log.none { it.startsWith("composables") }) kotlinx.coroutines.delay(5) }
        assertTrue("composables(10,skipSystem=false,allowEmpty=false)" in log)

        // 실시간을 끄고 새로 고치면 continuous로 받고 다시 멈춘다.
        s.setLive(false)
        log.clear()
        view.onStartFetch = { view.events.emit(layout(10)) }
        s.refresh()
        assertEquals("start(true)", log.first())
        assertEquals("stop", log.last())
        assertTrue("composables(10,skipSystem=false,allowEmpty=false)" in log)
        s.close()
    }

    @Test
    fun resendsDefaultSettingsWhenInspectorDoesNotKnowThem() = runBlocking {
        compose.unknownSettings = true
        compose.applySettings()
        assertEquals(listOf("settings(keep=false,reduce=true)", "settings(keep=false,reduce=false)"), log.toList())
    }

    @Test
    fun propertiesRouteByNodeKind() = runBlocking {
        val s = session()
        val viewNode = LayoutNode(5, LayoutNode.Kind.View, "TextView", null, NodeBounds(0, 0, 1, 1), emptyList(), rootId = 10)
        val composable = viewNode.copy(id = -7, kind = LayoutNode.Kind.Composable, anchorHash = 99)
        s.properties(viewNode)
        s.properties(composable)
        assertEquals(listOf("props(10,5)", "params(10,-7,99)"), log.toList())
    }
}
