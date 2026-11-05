package io.github.eoeo0326.adbmirror.core.inspector

import com.google.protobuf.ByteString
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.AppInspectionCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.AppInspectionResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.CreateInspectorCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.CreateInspectorResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.DisposeInspectorCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.GetLibraryCompatibilityInfoCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.LaunchMetadata
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.LibraryCompatibility
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.LibraryCompatibilityInfo
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.RawCommand
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Command
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** App Inspection 명령이 실패했다(`status == ERROR`, 시간 초과, payload 누락). */
class AppInspectionException(message: String) : Exception(message)

/** inspector가 보낸 이벤트. */
sealed interface InspectorRawEvent {
    val inspectorId: String

    data class Raw(override val inspectorId: String, val bytes: ByteArray) : InspectorRawEvent

    /** inspector가 폐기됐다(앱 종료·다른 클라이언트가 force로 대체 등). */
    data class Disposed(override val inspectorId: String, val errorMessage: String) : InspectorRawEvent
}

/**
 * 프로세스 하나의 App Inspection 클라이언트. transport 이벤트 중 그 pid 것만 받아
 * 응답은 `command_id`로 짝을 맞추고, inspector 이벤트는 [events]로 흘린다.
 *
 * 큰 응답·이벤트는 `payload_id`만 담고 오며, 내용은 같은 `group_id`의 `APP_INSPECTION_PAYLOAD` 조각으로 **먼저** 온다.
 * 이벤트는 수집기 하나가 순서대로 처리하므로 참조가 올 때는 조각이 이미 모여 있다.
 */
class AppInspectionClient(
    private val transport: TransportClient,
    private val deviceId: Long,
    val pid: Int,
    scope: CoroutineScope,
    private val timeoutMs: Long = 15_000,
) {
    private val nextCommandId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<AppInspectionResponse>>()
    private val payloads = PayloadAssembler()
    private val _events = MutableSharedFlow<InspectorRawEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<InspectorRawEvent> = _events.asSharedFlow()

    // 명령을 보내기 전에 구독이 끝나도록 UNDISPATCHED로 시작한다.
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        transport.events.filter { it.pid == pid }.collect(::handle)
    }

    private suspend fun handle(event: Event) {
        when (event.kind) {
            Event.Kind.APP_INSPECTION_PAYLOAD -> payloads.add(event.groupId, event.appInspectionPayload)
            Event.Kind.APP_INSPECTION_RESPONSE -> {
                val response = event.appInspectionResponse
                pending.remove(response.commandId)?.complete(response)
            }
            Event.Kind.APP_INSPECTION_EVENT -> {
                val e = event.appInspectionEvent
                when {
                    e.hasRawEvent() -> {
                        val raw = e.rawEvent
                        val bytes = if (raw.hasPayloadId()) payloads.take(raw.payloadId) else raw.content.toByteArray()
                        // 조각을 놓친 이벤트는 버린다. 실시간 갱신이면 다음 이벤트가 곧 온다.
                        if (bytes != null) _events.emit(InspectorRawEvent.Raw(e.inspectorId, bytes))
                    }
                    e.hasDisposedEvent() -> _events.emit(InspectorRawEvent.Disposed(e.inspectorId, e.disposedEvent.errorMessage))
                }
            }
            else -> Unit
        }
    }

    /** 명령 하나를 보내고 응답을 기다린다. `ERROR`면 [AppInspectionException]. */
    suspend fun send(inspectorId: String, fill: AppInspectionCommand.Builder.() -> Unit): AppInspectionResponse {
        val id = nextCommandId.getAndIncrement()
        val deferred = CompletableDeferred<AppInspectionResponse>()
        pending[id] = deferred
        try {
            val command = AppInspectionCommand.newBuilder().setInspectorId(inspectorId).setCommandId(id).apply(fill).build()
            transport.execute(
                Command.newBuilder().setStreamId(deviceId).setPid(pid).setType(Command.CommandType.APP_INSPECTION)
                    .setAppInspectionCommand(command).build(),
            )
            val response = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw AppInspectionException("$inspectorId 명령 $id 응답이 ${timeoutMs}ms 안에 오지 않았습니다")
            }
            if (response.status == AppInspectionResponse.Status.ERROR) {
                throw AppInspectionException("$inspectorId: ${response.errorMessage.ifEmpty { "알 수 없는 오류" }}")
            }
            return response
        } finally {
            pending.remove(id)
        }
    }

    suspend fun createInspector(inspectorId: String, dexPath: String, minLibrary: LibraryCompatibility? = null): CreateInspectorResponse.Status {
        val response = send(inspectorId) {
            setCreateInspectorCommand(
                CreateInspectorCommand.newBuilder().setDexPath(dexPath).setLaunchMetadata(
                    LaunchMetadata.newBuilder().setLaunchedByName(LAUNCHED_BY)
                        // 이전 세션(또는 Studio)이 남긴 같은 inspector를 대체한다.
                        .setForce(true)
                        .apply { minLibrary?.let(::setMinLibrary) },
                ),
            )
        }
        return response.createInspectorResponse.status
    }

    suspend fun disposeInspector(inspectorId: String) {
        send(inspectorId) { setDisposeInspectorCommand(DisposeInspectorCommand.getDefaultInstance()) }
    }

    /** inspector별 Command 바이트를 보내고 Response 바이트를 돌려준다. */
    suspend fun raw(inspectorId: String, command: ByteArray): ByteArray {
        val response = send(inspectorId) { setRawInspectorCommand(RawCommand.newBuilder().setContent(ByteString.copyFrom(command))) }
        val raw = response.rawResponse
        return if (raw.hasPayloadId()) {
            payloads.take(raw.payloadId) ?: throw AppInspectionException("$inspectorId 응답 payload ${raw.payloadId}가 완전하지 않습니다")
        } else {
            raw.content.toByteArray()
        }
    }

    suspend fun libraryCompatibility(vararg targets: LibraryCompatibility): List<LibraryCompatibilityInfo> {
        // inspector_id는 비워도 된다(라이브러리 확인은 inspector에 매이지 않는다).
        val response = send("") {
            setGetLibraryCompatibilityInfoCommand(GetLibraryCompatibilityInfoCommand.newBuilder().addAllTargetLibraries(targets.toList()))
        }
        return response.getLibraryCompatibilityResponse.responsesList
    }

    fun close() {
        job.cancel()
        pending.values.forEach { it.completeExceptionally(AppInspectionException("연결을 닫았습니다")) }
    }

    companion object {
        const val LAUNCHED_BY = "ADB Mirror"
    }
}
