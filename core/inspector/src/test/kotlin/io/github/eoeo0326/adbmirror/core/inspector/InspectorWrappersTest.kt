package io.github.eoeo0326.adbmirror.core.inspector

import com.google.protobuf.ByteString
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.AppInspectionEvent
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.AppInspectionResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.RawEvent
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.RawResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Event
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectorWrappersTest {
    private val pid = 7
    private val server = FakeTransportServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = TransportClient(server.channel(), scope)

    @AfterTest
    fun tearDown() {
        transport.close()
        scope.cancel()
        server.shutdown()
    }

    private suspend fun client(): AppInspectionClient {
        withTimeout(5_000) { server.subscribed.await() }
        return AppInspectionClient(transport, 42, pid, scope, timeoutMs = 2_000)
    }

    /** 받은 raw 명령을 [answer]로 답한다. */
    private fun answerRaw(answer: (inspectorId: String, content: ByteArray) -> ByteArray) {
        server.onCommand = { c ->
            val cmd = c.appInspectionCommand
            server.events.emit(
                Event.newBuilder().setKind(Event.Kind.APP_INSPECTION_RESPONSE).setPid(pid).setAppInspectionResponse(
                    AppInspectionResponse.newBuilder().setCommandId(cmd.commandId).setRawResponse(
                        RawResponse.newBuilder().setContent(ByteString.copyFrom(answer(cmd.inspectorId, cmd.rawInspectorCommand.content.toByteArray()))),
                    ),
                ).build(),
            )
        }
    }

    @Test
    fun viewStartFetchRoundTripsAndEventsAreFilteredByInspector() = runBlocking {
        var received: ViewLayoutInspection.Command? = null
        answerRaw { id, bytes ->
            assertEquals(ViewInspector.ID, id)
            received = ViewLayoutInspection.Command.parseFrom(bytes)
            ViewLayoutInspection.Response.newBuilder().setStartFetchResponse(
                ViewLayoutInspection.StartFetchResponse.newBuilder().setCode(ViewLayoutInspection.ErrorCode.NO_ROOT_VIEWS_FOUND).setError("none"),
            ).build().toByteArray()
        }
        val view = ViewInspector(client())
        val response = view.startFetch(continuous = true)
        assertTrue(received!!.startFetchCommand.continuous)
        assertEquals(ViewLayoutInspection.ErrorCode.NO_ROOT_VIEWS_FOUND, response.code)

        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { view.events.first() } }
        fun raw(id: String, bytes: ByteArray) = Event.newBuilder().setKind(Event.Kind.APP_INSPECTION_EVENT).setPid(pid)
            .setAppInspectionEvent(AppInspectionEvent.newBuilder().setInspectorId(id).setRawEvent(RawEvent.newBuilder().setContent(ByteString.copyFrom(bytes)))).build()
        server.events.emit(raw(ComposeInspector.ID, byteArrayOf(1, 2, 3)))
        server.events.emit(raw(ViewInspector.ID, ViewLayoutInspection.Event.newBuilder().setRootsEvent(ViewLayoutInspection.WindowRootsEvent.newBuilder().addIds(5)).build().toByteArray()))
        assertEquals(listOf(5L), event.await().rootsEvent.idsList)
    }

    @Test
    fun composeCommandsCarryArguments() = runBlocking {
        val received = mutableListOf<ComposeLayoutInspection.Command>()
        answerRaw { _, bytes ->
            received += ComposeLayoutInspection.Command.parseFrom(bytes)
            ComposeLayoutInspection.Response.newBuilder().setGetComposablesResponse(
                ComposeLayoutInspection.GetComposablesResponse.newBuilder().setUnchanged(true),
            ).build().toByteArray()
        }
        val compose = ComposeInspector(client())
        compose.updateSettings()
        val response = compose.composables(rootViewId = 9, skipSystem = true, generation = 3)
        assertTrue(response.getComposablesResponse.unchanged)
        val settings = received[0].updateSettingsCommand
        assertTrue(settings.includeRecomposeCounts && settings.delayParameterExtractions && settings.reduceChildNesting && !settings.keepRecomposeCounts)
        val get = received[1].getComposablesCommand
        assertEquals(9L, get.rootViewId)
        assertTrue(get.skipSystemComposables && get.allowEmptyIfUnchanged && !get.extractAllParameters)
        assertEquals(3, get.generation)
    }
}
