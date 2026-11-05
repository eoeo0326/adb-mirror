package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.adb.PortForward
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Command
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Event
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.ExecuteResponse
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.ServerServiceDefinition
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.kotlin.ServerCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

/**
 * in-process gRPC로 띄운 가짜 transport 데몬. 받은 명령을 [commands]에 남기고, [onCommand]로 이벤트를 흘린다.
 * [streams]는 GetEvents를 몇 번 열었는지, [subscribed]는 첫 스트림이 열렸는지 알려준다.
 * 실제 데몬처럼 `GetVersion`은 `UNIMPLEMENTED`로 답한다.
 */
class FakeTransportServer {
    val commands = CopyOnWriteArrayList<Command>()
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 1024)
    val streams = AtomicInteger()
    val subscribed = CompletableDeferred<Unit>()
    var onCommand: suspend (Command) -> Unit = {}
    private val name = InProcessServerBuilder.generateName()

    private val service = ServerServiceDefinition.builder(TransportClient.SERVICE)
        .addMethod(ServerCalls.unaryServerMethodDefinition(EmptyCoroutineContext, TransportClient.GET_VERSION) { throw Status.UNIMPLEMENTED.asException() })
        .addMethod(
            ServerCalls.unaryServerMethodDefinition(EmptyCoroutineContext, TransportClient.EXECUTE) { req ->
                commands += req.command
                onCommand(req.command)
                ExecuteResponse.getDefaultInstance()
            },
        )
        .addMethod(
            ServerCalls.serverStreamingServerMethodDefinition(EmptyCoroutineContext, TransportClient.GET_EVENTS) {
                streams.incrementAndGet()
                events.onSubscription { subscribed.complete(Unit) }
            },
        )
        .build()

    val server: Server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()

    fun channel(): ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()

    fun shutdown() {
        server.shutdownNow()
    }
}

/** 셸 명령에 정해 둔 응답을 돌려주고, 한 일을 [calls]에 남기는 가짜 기기. */
class FakeDeviceShell(
    var api: Int = 34,
    var abiList: String = "arm64-v8a,armeabi-v7a",
    var daemonRunning: Boolean = false,
    var remoteSha: Map<String, String> = emptyMap(),
    /** 데몬을 띄우면 이 줄들을 출력한다. */
    var daemonOutput: List<String> = listOf("Server listening on: 127.0.0.1:12389"),
) : DeviceShell {
    val calls = CopyOnWriteArrayList<String>()
    val pushed = CopyOnWriteArrayList<String>()
    val forwardsOpen = AtomicInteger()
    val daemonStops = AtomicInteger()

    override suspend fun shell(serial: String, command: String): String {
        calls += "shell $command"
        return when {
            command == "getprop ro.build.version.sdk" -> "$api\n"
            command == "getprop ro.product.cpu.abilist" -> "$abiList\n"
            command.startsWith("pidof transport") -> if (daemonRunning) "1234\n" else ""
            command.startsWith("sha256sum") -> remoteSha.entries.joinToString("\n") { "${it.value}  ${it.key}" }
            command == "cat /proc/sys/kernel/random/boot_id" -> "boot-1\n"
            else -> ""
        }
    }

    override suspend fun push(serial: String, local: File, remotePath: String) {
        pushed += remotePath
    }

    override suspend fun push(serial: String, data: ByteArray, remotePath: String) {
        pushed += remotePath
    }

    override suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit): RemoteProcess {
        calls += "start ${command.joinToString(" ")}"
        daemonOutput.forEach(onOutput)
        val exit = CompletableDeferred<Int>()
        return object : RemoteProcess {
            override suspend fun awaitExit(): Int = exit.await()
            override suspend fun stop() {
                daemonStops.incrementAndGet()
                exit.complete(0)
            }
        }
    }

    override suspend fun forward(serial: String, localAbstract: String): PortForward {
        calls += "forward $localAbstract"
        forwardsOpen.incrementAndGet()
        return PortForward(5000) { forwardsOpen.decrementAndGet() }
    }
}
