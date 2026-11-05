package io.github.eoeo0326.adbmirror.core.data.inspector

import io.github.eoeo0326.adbmirror.core.adb.PortForward
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorException
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import io.github.eoeo0326.adbmirror.core.inspector.DeviceShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InspectorRepositoryImplTest {
    private val settings = object : SettingsRepository {
        override val settings = MutableStateFlow(Settings(androidStudioPath = "/nowhere/Android Studio.app"))
        override suspend fun update(transform: (Settings) -> Settings) = settings.value.let { settings.value = transform(it) }
    }

    private val shell = object : DeviceShell {
        val calls = mutableListOf<String>()
        override suspend fun shell(serial: String, command: String): String { calls += command; return "" }
        override suspend fun push(serial: String, local: File, remotePath: String) { calls += "push" }
        override suspend fun push(serial: String, data: ByteArray, remotePath: String) { calls += "push" }
        override suspend fun startProcess(serial: String, command: List<String>, onOutput: (String) -> Unit): RemoteProcess = error("unused")
        override suspend fun forward(serial: String, localAbstract: String): PortForward = error("unused")
    }

    @Test
    fun failsWithStudioNotFoundBeforeTouchingDevice() = runBlocking {
        var asked: String? = "unset"
        val repo = InspectorRepositoryImpl(shell, settings, Files.createTempDirectory("cache").toFile(), locate = { asked = it; null })
        val steps = mutableListOf<InspectorStep>()
        val e = assertFailsWith<InspectorException> { repo.connect("S1") { steps += it } }
        assertEquals(InspectorFailure.StudioNotFound, e.failure)
        assertEquals("/nowhere/Android Studio.app", asked, "설정한 위치를 먼저 넘긴다")
        assertEquals(listOf<InspectorStep>(InspectorStep.LocatingStudio), steps)
        assertTrue(shell.calls.isEmpty())
    }
}
