package io.github.eoeo0326.adbmirror.core.inspector

import com.google.protobuf.Message
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AgentData
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AgentStatusRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AttachAgent
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Command
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Event
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.ExecuteRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.ExecuteResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.GetEventsRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.VersionRequest
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.VersionResponse
import io.grpc.Channel
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.kotlin.ClientCalls
import io.grpc.protobuf.ProtoUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * transport 데몬의 `TransportService` gRPC 클라이언트.
 *
 * 메시지만 protoc로 만들고 서비스 스텁은 생성하지 않으므로 메서드를 [MethodDescriptor]로 직접 정의한다.
 * 메서드 경로가 package에 들어가므로 proto package는 반드시 `profiler.proto`다.
 *
 * 기기 데몬은 `Execute`·`GetEvents`·`GetAgentStatus` 등만 처리한다. `GetVersion`·`GetDevices`·`GetProcesses`는 Android Studio가
 * 호스트 쪽 프록시(`TransportServiceProxy`)에서 ddmlib로 답하는 메서드라 데몬에 보내면 `UNIMPLEMENTED`다.
 * 그래서 기기 정보·프로세스 목록은 adb 셸로 구한다([ProcessLister]).
 *
 * 응답·이벤트는 모두 `GetEvents` 스트림 하나로 온다. 만들자마자 스트림을 열어 [events]로 흘리고(replay 없음),
 * 명령의 결과를 기다릴 때는 [executeAndAwait]로 **구독한 뒤에** 명령을 보낸다. 거꾸로 하면 응답을 놓친다.
 */
class TransportClient(private val channel: Channel, scope: CoroutineScope) {
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4096)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** 스트림을 연 뒤 받은 pid별 마지막 에이전트 상태. */
    private val _agents = MutableStateFlow<Map<Int, AgentData.Status>>(emptyMap())
    val agents: StateFlow<Map<Int, AgentData.Status>> = _agents.asStateFlow()

    /** 이벤트 스트림이 끝나면(데몬 종료·연결 끊김) 완료된다. 정상 종료면 null. */
    val disconnected = CompletableDeferred<Throwable?>()

    private val streamJob: Job = scope.launch {
        val cause = runCatching {
            ClientCalls.serverStreamingRpc(channel, GET_EVENTS, GetEventsRequest.getDefaultInstance()).collect { event ->
                if (event.kind == Event.Kind.AGENT && event.agentData.status != AgentData.Status.UNSPECIFIED) {
                    _agents.update { it + (event.pid to event.agentData.status) }
                }
                _events.emit(event)
            }
        }.exceptionOrNull()
        disconnected.complete(cause)
    }

    /**
     * 데몬 gRPC 서버에 닿는지 확인한다. 데몬은 `GetVersion`을 구현하지 않으므로 `UNIMPLEMENTED` 응답도 닿은 것으로 본다.
     * 닿지 않으면(`UNAVAILABLE` 등) 예외를 그대로 던진다.
     */
    suspend fun ping() {
        try {
            ClientCalls.unaryRpc(channel, GET_VERSION, VersionRequest.getDefaultInstance())
        } catch (e: StatusException) {
            if (e.status.code != Status.Code.UNIMPLEMENTED) throw e
        }
    }

    suspend fun execute(command: Command): Int =
        ClientCalls.unaryRpc(channel, EXECUTE, ExecuteRequest.newBuilder().setCommand(command).build()).commandId

    /** [select]가 null이 아닌 값을 내는 첫 이벤트를 기다리며 [command]를 보낸다. 구독이 먼저다. */
    suspend fun <T : Any> executeAndAwait(command: Command, timeoutMs: Long, select: (Event) -> T?): T = coroutineScope {
        // UNDISPATCHED라 first()가 SharedFlow를 구독할 때까지 여기서 바로 돈다.
        val result = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(timeoutMs) { events.mapNotNull(select).first() } }
        execute(command)
        result.await()
    }

    /** 데몬이 아는 그 프로세스의 에이전트 상태. 데몬이 구현하지 않으면 `UNSPECIFIED`. */
    suspend fun agentStatus(streamId: Long, pid: Int, packageName: String): AgentData.Status = try {
        ClientCalls.unaryRpc(
            channel,
            GET_AGENT_STATUS,
            AgentStatusRequest.newBuilder().setDeviceId(streamId).setPid(pid).setPackageName(packageName).build(),
        ).status
    } catch (e: StatusException) {
        if (e.status.code != Status.Code.UNIMPLEMENTED) throw e
        AgentData.Status.UNSPECIFIED
    }

    /**
     * 프로세스에 JVMTI 에이전트를 붙이고 상태를 기다린다. 시간 안에 정해지지 않으면 `UNSPECIFIED`.
     *
     * 이미 붙은 프로세스에 다시 `ATTACH_AGENT`를 보내면 데몬은 `AGENT` 이벤트를 새로 보내지 않는다.
     * 그래서 먼저 `GetAgentStatus`로 확인하고, 보낸 뒤에도 이벤트를 기다리는 동안 상태를 주기적으로 다시 묻는다.
     */
    suspend fun attachAgent(streamId: Long, pid: Int, packageName: String, cpuArch: String, timeoutMs: Long = 10_000): AgentData.Status {
        if (agentStatus(streamId, pid, packageName) == AgentData.Status.ATTACHED) return AgentData.Status.ATTACHED
        // 이전 시도에서 받은 상태(예: UNATTACHABLE)를 이번 결과로 착각하지 않도록, 보내기 전 값과 다른 것만 받는다.
        val before = agents.value[pid]
        if (before == AgentData.Status.ATTACHED) return AgentData.Status.ATTACHED
        val command = Command.newBuilder().setStreamId(streamId).setPid(pid).setType(Command.CommandType.ATTACH_AGENT)
            .setAttachAgent(
                AttachAgent.newBuilder().setAgentLibFileName(DeviceFiles.agentFileName(cpuArch))
                    .setAgentConfigPath(DeviceFiles.AGENT_CONFIG).setPackageName(packageName),
            ).build()
        execute(command)
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val fromEvent = withTimeoutOrNull(AGENT_POLL_MS) { agents.mapNotNull { it[pid] }.first { it != before } }
                if (fromEvent != null) return@withTimeoutOrNull fromEvent
                val polled = agentStatus(streamId, pid, packageName)
                if (polled != AgentData.Status.UNSPECIFIED) return@withTimeoutOrNull polled
            }
            @Suppress("UNREACHABLE_CODE")
            AgentData.Status.UNSPECIFIED
        } ?: AgentData.Status.UNSPECIFIED
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
        private const val AGENT_POLL_MS = 500L

        val GET_AGENT_STATUS: MethodDescriptor<AgentStatusRequest, AgentData> =
            method("GetAgentStatus", MethodDescriptor.MethodType.UNARY, AgentStatusRequest.getDefaultInstance(), AgentData.getDefaultInstance())
        val EXECUTE: MethodDescriptor<ExecuteRequest, ExecuteResponse> =
            method("Execute", MethodDescriptor.MethodType.UNARY, ExecuteRequest.getDefaultInstance(), ExecuteResponse.getDefaultInstance())
        val GET_EVENTS: MethodDescriptor<GetEventsRequest, Event> =
            method("GetEvents", MethodDescriptor.MethodType.SERVER_STREAMING, GetEventsRequest.getDefaultInstance(), Event.getDefaultInstance())
    }
}
