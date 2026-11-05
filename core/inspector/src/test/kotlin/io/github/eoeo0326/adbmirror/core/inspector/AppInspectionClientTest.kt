package io.github.eoeo0326.adbmirror.core.inspector

import com.google.protobuf.ByteString
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.AppInspectionEvent
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.AppInspectionPayload
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.AppInspectionResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.CreateInspectorResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.DisposedEvent
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.RawEvent
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.RawResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Command
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppInspectionClientTest {
    private val pid = 1234
    private val server = FakeTransportServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = TransportClient(server.channel(), scope)

    @AfterTest
    fun tearDown() {
        transport.close()
        scope.cancel()
        server.shutdown()
    }

    private suspend fun client(timeoutMs: Long = 2_000): AppInspectionClient {
        withTimeout(5_000) { server.subscribed.await() }
        return AppInspectionClient(transport, deviceId = 42, pid = pid, scope = scope, timeoutMs = timeoutMs)
    }

    private fun response(commandId: Int, fill: AppInspectionResponse.Builder.() -> Unit = {}) =
        Event.newBuilder().setKind(Event.Kind.APP_INSPECTION_RESPONSE).setPid(pid)
            .setAppInspectionResponse(AppInspectionResponse.newBuilder().setCommandId(commandId).apply(fill)).build()

    private fun payload(group: Long, index: Int, count: Int, bytes: ByteArray) =
        Event.newBuilder().setKind(Event.Kind.APP_INSPECTION_PAYLOAD).setPid(pid).setGroupId(group)
            .setAppInspectionPayload(AppInspectionPayload.newBuilder().setChunk(ByteString.copyFrom(bytes)).setChunkIndex(index).setChunkCount(count)).build()

    private val Command.inspection get() = appInspectionCommand

    @Test
    fun createInspectorSendsForceAndReadsStatus() = runBlocking {
        server.onCommand = { c ->
            server.events.emit(response(c.inspection.commandId) {
                setCreateInspectorResponse(CreateInspectorResponse.newBuilder().setStatus(CreateInspectorResponse.Status.SUCCESS))
            })
        }
        val status = client().createInspector("layoutinspector.view.inspection", "/data/local/tmp/perfd/view.jar")
        assertEquals(CreateInspectorResponse.Status.SUCCESS, status)
        val sent = server.commands.single()
        assertEquals(Command.CommandType.APP_INSPECTION, sent.type)
        assertEquals(42L, sent.streamId)
        assertEquals(pid, sent.pid)
        assertEquals(1, sent.inspection.commandId)
        assertTrue(sent.inspection.createInspectorCommand.launchMetadata.force)
        assertEquals("ADB Mirror", sent.inspection.createInspectorCommand.launchMetadata.launchedByName)
    }

    @Test
    fun rawResponseAssemblesOutOfOrderPayloadChunks() = runBlocking {
        server.onCommand = { c ->
            server.events.emit(payload(9, 1, 3, byteArrayOf(3, 4)))
            server.events.emit(payload(9, 0, 3, byteArrayOf(1, 2)))
            server.events.emit(payload(9, 2, 3, byteArrayOf(5)))
            server.events.emit(response(c.inspection.commandId) { setRawResponse(RawResponse.newBuilder().setPayloadId(9)) })
        }
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), client().raw("x", byteArrayOf(0)))
    }

    @Test
    fun rawResponseFailsWhenChunkMissing() = runBlocking {
        server.onCommand = { c ->
            server.events.emit(payload(9, 0, 2, byteArrayOf(1)))
            server.events.emit(response(c.inspection.commandId) { setRawResponse(RawResponse.newBuilder().setPayloadId(9)) })
        }
        assertFailsWith<AppInspectionException> { client().raw("x", byteArrayOf(0)) }
        Unit
    }

    @Test
    fun errorStatusCarriesMessage() = runBlocking {
        server.onCommand = { c ->
            server.events.emit(response(c.inspection.commandId) { setStatus(AppInspectionResponse.Status.ERROR).setErrorMessage("boom") })
        }
        val e = assertFailsWith<AppInspectionException> { client().raw("x", byteArrayOf(0)) }
        assertTrue("boom" in e.message!!)
    }

    @Test
    fun timesOutWithoutResponse() = runBlocking {
        val e = assertFailsWith<AppInspectionException> { client(timeoutMs = 200).raw("x", byteArrayOf(0)) }
        assertTrue("응답" in e.message!!)
    }

    @Test
    fun matchesConcurrentCommandsAnsweredOutOfOrder() = runBlocking {
        val held = mutableListOf<Command>()
        server.onCommand = { c ->
            synchronized(held) { held += c }
            if (synchronized(held) { held.size } == 2) {
                // 나중 명령부터 답한다. 응답 내용은 자기 command_id다.
                held.reversed().forEach { h ->
                    server.events.emit(response(h.inspection.commandId) {
                        setRawResponse(RawResponse.newBuilder().setContent(ByteString.copyFrom(byteArrayOf(h.inspection.commandId.toByte()))))
                    })
                }
            }
        }
        val client = client()
        val a = async(Dispatchers.Default) { client.raw("x", byteArrayOf(1)) }
        val b = async(Dispatchers.Default) { client.raw("x", byteArrayOf(2)) }
        val results = setOf(a.await()[0].toInt(), b.await()[0].toInt())
        assertEquals(setOf(1, 2), results)
    }

    @Test
    fun emitsInspectorEventsWithPayloadAndDisposal() = runBlocking {
        val client = client()
        val collected = async(start = CoroutineStart.UNDISPATCHED) { client.events.take(3).toList() }
        // 다른 프로세스 이벤트는 무시한다.
        server.events.emit(Event.newBuilder().setKind(Event.Kind.APP_INSPECTION_EVENT).setPid(999)
            .setAppInspectionEvent(AppInspectionEvent.newBuilder().setInspectorId("other").setRawEvent(RawEvent.newBuilder().setContent(ByteString.copyFrom(byteArrayOf(9))))).build())
        server.events.emit(Event.newBuilder().setKind(Event.Kind.APP_INSPECTION_EVENT).setPid(pid)
            .setAppInspectionEvent(AppInspectionEvent.newBuilder().setInspectorId("v").setRawEvent(RawEvent.newBuilder().setContent(ByteString.copyFrom(byteArrayOf(7))))).build())
        server.events.emit(payload(5, 0, 1, byteArrayOf(8, 8)))
        server.events.emit(Event.newBuilder().setKind(Event.Kind.APP_INSPECTION_EVENT).setPid(pid)
            .setAppInspectionEvent(AppInspectionEvent.newBuilder().setInspectorId("v").setRawEvent(RawEvent.newBuilder().setPayloadId(5))).build())
        server.events.emit(Event.newBuilder().setKind(Event.Kind.APP_INSPECTION_EVENT).setPid(pid)
            .setAppInspectionEvent(AppInspectionEvent.newBuilder().setInspectorId("v").setDisposedEvent(DisposedEvent.newBuilder().setErrorMessage("gone"))).build())
        val events = withTimeout(5_000) { collected.await() }
        assertContentEquals(byteArrayOf(7), (events[0] as InspectorRawEvent.Raw).bytes)
        assertContentEquals(byteArrayOf(8, 8), (events[1] as InspectorRawEvent.Raw).bytes)
        assertEquals(InspectorRawEvent.Disposed("v", "gone"), events[2])
    }

    @Test
    fun payloadAssemblerKeepsOnlyRecentGroups() {
        val assembler = PayloadAssembler(maxGroups = 2)
        fun chunk(b: Byte) = AppInspectionPayload.newBuilder().setChunk(ByteString.copyFrom(byteArrayOf(b))).setChunkCount(1).build()
        assembler.add(1, chunk(1))
        assembler.add(2, chunk(2))
        assembler.add(3, chunk(3))
        assertEquals(2, assembler.size())
        assertNull(assembler.take(1), "가장 오래된 그룹은 버린다")
        assertContentEquals(byteArrayOf(3), assembler.take(3))
        assertNull(assembler.take(3), "한 번 꺼내면 지운다")
    }

    @Test
    fun ignoresResponsesForUnknownCommands() = runBlocking {
        val client = client()
        server.events.emit(response(999))
        server.onCommand = { c -> server.events.emit(response(c.inspection.commandId) { setRawResponse(RawResponse.newBuilder().setContent(ByteString.copyFrom(byteArrayOf(1)))) }) }
        assertContentEquals(byteArrayOf(1), client.raw("x", byteArrayOf(0)))
        assertTrue(server.commands.first().hasAppInspectionCommand())
    }
}
