package io.github.eoeo0326.adbmirror.core.domain

import io.github.eoeo0326.adbmirror.core.domain.model.AppVersion
import io.github.eoeo0326.adbmirror.core.domain.model.DownloadedUpdate
import io.github.eoeo0326.adbmirror.core.domain.model.InstallKind
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateInfo
import io.github.eoeo0326.adbmirror.core.domain.repository.UpdateRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.CheckForUpdateUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.InstallUpdateUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {
    private class FakeUpdates(var latestVersion: String = "0.9.0", override val installKind: InstallKind = InstallKind.MacApp) : UpdateRepository {
        val installed = mutableListOf<DownloadedUpdate>()
        override suspend fun latest() = UpdateInfo(AppVersion.parse(latestVersion)!!, "notes")
        override suspend fun download(info: UpdateInfo, onProgress: (Float?) -> Unit) = DownloadedUpdate(info, "file")
        override suspend fun install(update: DownloadedUpdate) { installed += update }
        override fun manualCommand(update: DownloadedUpdate) = "cmd"
    }

    @Test
    fun versionParsesTagAndPlainAndComparesNumerically() {
        assertEquals(AppVersion(0, 8, 0), AppVersion.parse("v0.8.0"))
        assertEquals(AppVersion(1, 10, 2), AppVersion.parse(" 1.10.2 "))
        assertNull(AppVersion.parse("0.8"))
        assertNull(AppVersion.parse("v0.8.0-beta"))
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.9")!!)
        assertEquals("0.8.0", AppVersion(0, 8, 0).toString())
    }

    @Test
    fun onlyNewerReleaseIsOffered() = runTest {
        val current = AppVersion(0, 8, 0)
        assertEquals(AppVersion(0, 9, 0), CheckForUpdateUseCase(FakeUpdates("0.9.0"), current)()?.version)
        assertNull(CheckForUpdateUseCase(FakeUpdates("0.8.0"), current)())
        assertNull(CheckForUpdateUseCase(FakeUpdates("0.7.0"), current)())
    }

    @Test
    fun linuxPackageIsNotInstalledByApp() = runTest {
        val update = DownloadedUpdate(UpdateInfo(AppVersion(0, 9, 0), "n"), "f")
        val deb = FakeUpdates(installKind = InstallKind.LinuxDeb)
        assertFailsWith<IllegalStateException> { InstallUpdateUseCase(deb)(update) }
        assertTrue(deb.installed.isEmpty())
        val mac = FakeUpdates(installKind = InstallKind.MacApp)
        InstallUpdateUseCase(mac)(update)
        assertEquals(listOf(update), mac.installed)
    }
}
