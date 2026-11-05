package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorException
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
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
import kotlin.test.assertTrue

class InspectorConnectionImplTest {
    private val server = FakeTransportServer()
    private val base = Files.createTempDirectory("studio").toFile()
    private val install = StudioInstall(base, File(base, "resources"), "262.1", "Android Studio 2026.2.1").also { i ->
        i.requiredFiles().forEach { it.parentFile.mkdirs(); it.writeText(it.name) }
    }
    private val process = InspectableProcess(77, "com.example", "com.example", "arm64")

    @AfterTest
    fun tearDown() = server.shutdown()

    private suspend fun connect(shell: FakeDeviceShell): InspectorConnectionImpl {
        val transport = TransportLauncher(shell, channelFactory = { server.channel() }).launch("S1", install)
        withTimeout(5_000) { server.subscribed.await() }
        return InspectorConnectionImpl(install, transport, shell, ComposeInspectorResolver(base) { error("no network") })
    }

    @Test
    fun unattachableProcessFailsAndCloseRestoresDeviceState() = runBlocking {
        val shell = FakeDeviceShell()
        val connection = connect(shell)
        assertEquals("Android Studio 2026.2.1", connection.studioLabel)
        server.onCommand = { c ->
            if (c.type == Command.CommandType.ATTACH_AGENT) {
                server.events.emit(Event.newBuilder().setKind(Event.Kind.AGENT).setPid(c.pid).setAgentData(AgentData.newBuilder().setStatus(AgentData.Status.UNATTACHABLE)).build())
            }
        }
        val steps = mutableListOf<InspectorStep>()
        val e = assertFailsWith<InspectorException> { connection.inspect(process) { steps += it } }
        assertEquals(InspectorFailure.AttachFailed, e.failure)
        assertEquals(listOf<InspectorStep>(InspectorStep.Attaching), steps)
        assertTrue("shell settings put global debug_view_attributes 1" in shell.calls)

        connection.close()
        connection.close()
        // 원래 값이 비어 있었으면 지워서 되돌린다.
        assertEquals(1, shell.calls.count { it == "shell settings delete global debug_view_attributes" })
        assertEquals(0, shell.forwardsOpen.get())
        assertEquals(1, shell.daemonStops.get())
        assertFailsWith<IllegalStateException> { connection.inspect(process) }
        Unit
    }

    @Test
    fun keepsAttributeUntouchedWhenAlreadyEnabled() = runBlocking {
        val shell = object : DeviceShell by FakeDeviceShell() {
            val calls = mutableListOf<String>()
            override suspend fun shell(serial: String, command: String): String {
                calls += command
                return if (command == "settings get global debug_view_attributes") "1\n" else (FakeDeviceShell().shell(serial, command))
            }
        }
        val fake = FakeDeviceShell()
        val transport = TransportLauncher(fake, channelFactory = { server.channel() }).launch("S1", install)
        val connection = InspectorConnectionImpl(install, transport, shell, ComposeInspectorResolver(base) { error("no network") })
        server.onCommand = { c ->
            if (c.type == Command.CommandType.ATTACH_AGENT) {
                server.events.emit(Event.newBuilder().setKind(Event.Kind.AGENT).setPid(c.pid).setAgentData(AgentData.newBuilder().setStatus(AgentData.Status.UNATTACHABLE)).build())
            }
        }
        assertFailsWith<InspectorException> { connection.inspect(process) }
        connection.close()
        assertTrue(shell.calls.none { it.startsWith("settings put") || it.startsWith("settings delete") })
    }
}
