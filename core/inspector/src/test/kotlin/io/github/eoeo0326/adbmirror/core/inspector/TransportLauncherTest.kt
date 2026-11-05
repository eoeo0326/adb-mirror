package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectorException
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AgentData
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Command
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Event
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportLauncherTest {
    private val server = FakeTransportServer()
    private val install: StudioInstall = run {
        val base = Files.createTempDirectory("studio").toFile()
        val install = StudioInstall(base, File(base, "resources"), "262.1", "Android Studio test")
        install.requiredFiles().forEach { it.parentFile.mkdirs(); it.writeText(it.name) }
        install
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun launcher(shell: FakeDeviceShell) = TransportLauncher(shell, channelFactory = { server.channel() }, daemonStartTimeoutMs = 2_000)

    @Test
    fun startsDaemonPushesFilesAndCleansUpInOrder() = runBlocking {
        val shell = FakeDeviceShell()
        val connection = launcher(shell).launch("S1", install)
        assertEquals(TransportLauncher.deviceId("S1", "boot-1"), connection.deviceId)
        assertFalse(connection.reusedDaemon)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), connection.abis)
        assertTrue("start /data/local/tmp/perfd/transport -config_file=/data/local/tmp/perfd/daemon.config" in shell.calls)
        assertEquals(
            setOf(
                DeviceFiles.DAEMON, DeviceFiles.PERFA_JAR, DeviceFiles.agentPath("arm64"), DeviceFiles.agentPath("arm"),
                DeviceFiles.VIEW_INSPECTOR_JAR, DeviceFiles.DAEMON_CONFIG, DeviceFiles.AGENT_CONFIG,
            ),
            shell.pushed.toSet(),
        )
        assertEquals(1, shell.forwardsOpen.get())
        assertTrue("shell rm -f ${DeviceFiles.VIEW_INSPECTOR_JAR}" in shell.calls, "읽기 전용 jar 위로 push하지 않는다")
        assertTrue(shell.calls.any { it.startsWith("shell chmod 755 ${DeviceFiles.DAEMON}; chmod 444 ") && DeviceFiles.VIEW_INSPECTOR_JAR in it && DeviceFiles.PERFA_JAR in it })

        connection.close()
        connection.close()
        assertEquals(0, shell.forwardsOpen.get())
        assertEquals(1, shell.daemonStops.get(), "우리가 띄운 데몬은 한 번만 끈다")
        assertTrue(shell.calls.any { it.startsWith("shell pkill -f /data/local/tmp/perfd/transport") })
    }

    @Test
    fun reusesRunningDaemonAndLeavesItAlone() = runBlocking {
        val shell = FakeDeviceShell(daemonRunning = true)
        val connection = launcher(shell).launch("S1", install)
        assertTrue(connection.reusedDaemon)
        assertTrue(shell.calls.none { it.startsWith("start ") })
        connection.close()
        assertEquals(0, shell.daemonStops.get())
        assertTrue(shell.calls.none { it.contains("pkill") })
        assertEquals(0, shell.forwardsOpen.get())
    }

    @Test
    fun skipsPushWhenDeviceShaMatches() = runBlocking {
        val perfaSha = TransportLauncher.sha256Hex(install.perfaJar.readBytes())
        val configSha = TransportLauncher.sha256Hex(DeviceFiles.daemonConfig())
        val shell = FakeDeviceShell(remoteSha = mapOf(DeviceFiles.PERFA_JAR to perfaSha, DeviceFiles.DAEMON_CONFIG to configSha))
        launcher(shell).launch("S1", install).close()
        assertFalse(DeviceFiles.PERFA_JAR in shell.pushed)
        assertFalse(DeviceFiles.DAEMON_CONFIG in shell.pushed)
        assertTrue(DeviceFiles.DAEMON in shell.pushed)
    }

    @Test
    fun rejectsOldApiBeforeTouchingDevice() = runBlocking {
        val shell = FakeDeviceShell(api = 28)
        val e = assertFailsWith<InspectorException> { launcher(shell).launch("S1", install) }
        assertEquals(InspectorFailure.ApiTooLow, e.failure)
        assertTrue(shell.pushed.isEmpty())
    }

    @Test
    fun rollsBackWhenDaemonNeverReady() = runBlocking {
        val shell = FakeDeviceShell(daemonOutput = emptyList())
        val e = assertFailsWith<InspectorException> {
            TransportLauncher(shell, channelFactory = { server.channel() }, daemonStartTimeoutMs = 200).launch("S1", install)
        }
        assertEquals(InspectorFailure.InspectorFailed, e.failure)
        assertEquals(1, shell.daemonStops.get())
        assertEquals(0, shell.forwardsOpen.get())
    }

    @Test
    fun executeAndAwaitSubscribesBeforeSending() = runBlocking {
        // 서버가 명령을 받는 즉시 응답 이벤트를 보내도 놓치지 않아야 한다.
        server.onCommand = { command ->
            server.events.emit(
                Event.newBuilder().setKind(Event.Kind.AGENT).setPid(command.pid)
                    .setAgentData(AgentData.newBuilder().setStatus(AgentData.Status.ATTACHED)).build(),
            )
        }
        val connection = launcher(FakeDeviceShell()).launch("S1", install)
        withTimeout(5_000) { server.subscribed.await() }
        val status = connection.client.executeAndAwait(
            Command.newBuilder().setStreamId(connection.deviceId).setPid(77).setType(Command.CommandType.ATTACH_AGENT).build(),
            timeoutMs = 2_000,
        ) { e -> e.agentData.status.takeIf { e.kind == Event.Kind.AGENT && e.pid == 77 } }
        assertEquals(AgentData.Status.ATTACHED, status)
        assertEquals(1, server.streams.get())
        connection.close()
    }

    @Test
    fun failsAsDisconnectedWhenDaemonUnreachableAndRollsBack() = runBlocking {
        val shell = FakeDeviceShell()
        // 아무도 듣지 않는 in-process 이름이면 UNAVAILABLE이다(UNIMPLEMENTED만 연결된 것으로 본다).
        val nowhere = { _: Int -> io.grpc.inprocess.InProcessChannelBuilder.forName("nowhere-${System.nanoTime()}").build() }
        val e = assertFailsWith<InspectorException> { TransportLauncher(shell, channelFactory = nowhere).launch("S1", install) }
        assertEquals(InspectorFailure.Disconnected, e.failure)
        assertEquals(0, shell.forwardsOpen.get())
        assertEquals(1, shell.daemonStops.get())
    }

    @Test
    fun attachAgentResolvesFromEarlierEventWhenAlreadyAttached() = runBlocking {
        // 스트림을 열자마자 이전 이벤트로 ATTACHED가 오고, 다시 attach해도 새 이벤트는 없다.
        val connection = launcher(FakeDeviceShell()).launch("S1", install)
        withTimeout(5_000) { server.subscribed.await() }
        server.events.emit(Event.newBuilder().setKind(Event.Kind.AGENT).setPid(55).setAgentData(AgentData.newBuilder().setStatus(AgentData.Status.ATTACHED)).build())
        withTimeout(5_000) { connection.client.agents.first { 55 in it } }
        val status = connection.client.attachAgent(connection.deviceId, 55, "com.example", "arm64", timeoutMs = 1_000)
        assertEquals(AgentData.Status.ATTACHED, status)
        assertTrue(server.commands.isEmpty(), "이미 붙은 프로세스에는 다시 보내지 않는다")
        // 아무 소식이 없으면 UNSPECIFIED.
        assertEquals(AgentData.Status.UNSPECIFIED, connection.client.attachAgent(connection.deviceId, 66, "com.example", "arm64", timeoutMs = 200))
        val sent = server.commands.single()
        assertEquals(66, sent.pid)
        assertEquals("libjvmtiagent_arm64.so", sent.attachAgent.agentLibFileName)
        assertEquals(DeviceFiles.AGENT_CONFIG, sent.attachAgent.agentConfigPath)
        assertEquals("com.example", sent.attachAgent.packageName)
        connection.close()
    }

    @Test
    fun attachAgentIgnoresStaleStatusFromEarlierAttempt() = runBlocking {
        val connection = launcher(FakeDeviceShell()).launch("S1", install)
        withTimeout(5_000) { server.subscribed.await() }
        fun agent(status: AgentData.Status) =
            Event.newBuilder().setKind(Event.Kind.AGENT).setPid(77).setAgentData(AgentData.newBuilder().setStatus(status)).build()
        // 이전 시도의 UNATTACHABLE이 남아 있다.
        server.events.emit(agent(AgentData.Status.UNATTACHABLE))
        withTimeout(5_000) { connection.client.agents.first { 77 in it } }
        server.onCommand = { server.events.emit(agent(AgentData.Status.ATTACHED)) }
        assertEquals(AgentData.Status.ATTACHED, connection.client.attachAgent(connection.deviceId, 77, "com.example", "arm64", timeoutMs = 2_000))
        connection.close()
    }
}
