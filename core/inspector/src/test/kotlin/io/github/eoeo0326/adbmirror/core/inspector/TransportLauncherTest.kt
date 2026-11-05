package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AgentData
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Command
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.Event
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
        assertEquals(42L, connection.deviceId)
        assertEquals("fake-1", connection.daemonVersion)
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
}
