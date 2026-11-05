package io.github.eoeo0326.adbmirror.core.inspector

import com.google.protobuf.Message
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Command
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Device
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Event
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.ExecuteRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.ExecuteResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.GetDevicesRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.GetDevicesResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.GetEventsRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.GetProcessesRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.GetProcessesResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Process
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.VersionRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.VersionResponse
import io.grpc.Channel
import io.grpc.MethodDescriptor
import io.grpc.kotlin.ClientCalls
import io.grpc.protobuf.ProtoUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * transport 데몬의 `TransportService` gRPC 클라이언트.
 *
 * 메시지만 protoc로 만들고 서비스 스텁은 생성하지 않으므로 메서드를 [MethodDescriptor]로 직접 정의한다.
 * 메서드 경로가 package에 들어가므로 proto package는 반드시 `profiler.proto`다.
 *
 * 응답·이벤트는 모두 `GetEvents` 스트림 하나로 온다. 만들자마자 스트림을 열어 [events]로 흘리고(replay 없음),
 * 명령의 결과를 기다릴 때는 [executeAndAwait]로 **구독한 뒤에** 명령을 보낸다. 거꾸로 하면 응답을 놓친다.
 */
class TransportClient(private val channel: Channel, scope: CoroutineScope) {
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4096)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** 이벤트 스트림이 끝나면(데몬 종료·연결 끊김) 완료된다. 정상 종료면 null. */
    val disconnected = CompletableDeferred<Throwable?>()

    private val streamJob: Job = scope.launch {
        val cause = runCatching {
            ClientCalls.serverStreamingRpc(channel, GET_EVENTS, GetEventsRequest.getDefaultInstance()).collect { _events.emit(it) }
        }.exceptionOrNull()
        disconnected.complete(cause)
    }

    suspend fun version(): String = ClientCalls.unaryRpc(channel, GET_VERSION, VersionRequest.getDefaultInstance()).version

    suspend fun devices(): List<Device> = ClientCalls.unaryRpc(channel, GET_DEVICES, GetDevicesRequest.getDefaultInstance()).deviceList

    suspend fun processes(deviceId: Long): List<Process> =
        ClientCalls.unaryRpc(channel, GET_PROCESSES, GetProcessesRequest.newBuilder().setDeviceId(deviceId).build()).processList

    suspend fun execute(command: Command): Int =
        ClientCalls.unaryRpc(channel, EXECUTE, ExecuteRequest.newBuilder().setCommand(command).build()).commandId

    /** [select]가 null이 아닌 값을 내는 첫 이벤트를 기다리며 [command]를 보낸다. 구독이 먼저다. */
    suspend fun <T : Any> executeAndAwait(command: Command, timeoutMs: Long, select: (Event) -> T?): T = coroutineScope {
        // UNDISPATCHED라 first()가 SharedFlow를 구독할 때까지 여기서 바로 돈다.
        val result = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(timeoutMs) { events.mapNotNull(select).first() } }
        execute(command)
        result.await()
    }

    fun close() {
        streamJob.cancel()
    }

    companion object {
        const val SERVICE = "profiler.proto.TransportService"

        private fun <Req : Message, Res : Message> method(name: String, type: MethodDescriptor.MethodType, req: Req, res: Res) =
            MethodDescriptor.newBuilder(ProtoUtils.marshaller(req), ProtoUtils.marshaller(res))
                .setType(type)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, name))
                .build()

        val GET_VERSION: MethodDescriptor<VersionRequest, VersionResponse> =
            method("GetVersion", MethodDescriptor.MethodType.UNARY, VersionRequest.getDefaultInstance(), VersionResponse.getDefaultInstance())
        val GET_DEVICES: MethodDescriptor<GetDevicesRequest, GetDevicesResponse> =
            method("GetDevices", MethodDescriptor.MethodType.UNARY, GetDevicesRequest.getDefaultInstance(), GetDevicesResponse.getDefaultInstance())
        val GET_PROCESSES: MethodDescriptor<GetProcessesRequest, GetProcessesResponse> =
            method("GetProcesses", MethodDescriptor.MethodType.UNARY, GetProcessesRequest.getDefaultInstance(), GetProcessesResponse.getDefaultInstance())
        val EXECUTE: MethodDescriptor<ExecuteRequest, ExecuteResponse> =
            method("Execute", MethodDescriptor.MethodType.UNARY, ExecuteRequest.getDefaultInstance(), ExecuteResponse.getDefaultInstance())
        val GET_EVENTS: MethodDescriptor<GetEventsRequest, Event> =
            method("GetEvents", MethodDescriptor.MethodType.SERVER_STREAMING, GetEventsRequest.getDefaultInstance(), Event.getDefaultInstance())
    }
}
