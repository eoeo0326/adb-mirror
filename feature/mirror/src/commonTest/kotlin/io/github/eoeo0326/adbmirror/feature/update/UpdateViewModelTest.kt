package io.github.eoeo0326.adbmirror.feature.update

import io.github.eoeo0326.adbmirror.core.domain.model.AppVersion
import io.github.eoeo0326.adbmirror.core.domain.model.DownloadedUpdate
import io.github.eoeo0326.adbmirror.core.domain.model.InstallKind
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateInfo
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateStatus
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.UpdateRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.CheckForUpdateUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DownloadUpdateUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.InstallUpdateUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val current = AppVersion(0, 8, 0)
    private val v090 = UpdateInfo(AppVersion(0, 9, 0), "https://example/notes")

    private class FakeUpdates(override val installKind: InstallKind) : UpdateRepository {
        var latest = UpdateInfo(AppVersion(0, 9, 0), "https://example/notes")
        var failCheck: String? = null
        var failDownload: String? = null
        val calls = mutableListOf<String>()
        override suspend fun latest(): UpdateInfo {
            calls += "check"
            failCheck?.let { error(it) }
            return latest
        }
        override suspend fun download(info: UpdateInfo, onProgress: (Float?) -> Unit): DownloadedUpdate {
            calls += "download ${info.version}"
            failDownload?.let { error(it) }
            onProgress(0.5f)
            onProgress(1f)
            return DownloadedUpdate(info, "/data/updates/ADB-Mirror-${info.version}.bin")
        }
        override suspend fun install(update: DownloadedUpdate) { calls += "install ${update.file}" }
        override fun manualCommand(update: DownloadedUpdate) = "sudo apt install '${update.file}'"
    }

    private fun vm(updates: FakeUpdates, auto: Boolean): UpdateViewModel {
        val settings = object : SettingsRepository {
            override val settings = MutableStateFlow(Settings(autoUpdate = auto))
            override suspend fun update(transform: (Settings) -> Settings) {}
        }
        val actions = UpdateActions(
            current,
            updates.installKind,
            CheckForUpdateUseCase(updates, current),
            DownloadUpdateUseCase(updates),
            InstallUpdateUseCase(updates),
            updates::manualCommand,
        )
        return UpdateViewModel(actions, GetSettingsUseCase(settings))
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun autoOffDoesNotCheckAtStart() {
        val updates = FakeUpdates(InstallKind.MacApp)
        val vm = vm(updates, auto = false)
        assertEquals(UpdateStatus.Idle, vm.state.value.status)
        assertTrue(updates.calls.isEmpty())
    }

    @Test
    fun autoOnChecksDownloadsAndAsksToInstall() = runTest {
        val updates = FakeUpdates(InstallKind.MacApp)
        val vm = vm(updates, auto = true)
        val status = assertIs<UpdateStatus.Ready>(vm.state.value.status)
        assertEquals(v090, status.update.info)
        assertTrue(vm.state.value.showBanner)
        assertEquals(listOf("check", "download 0.9.0"), updates.calls, "설치는 묻기 전에 하지 않는다")

        vm.onIntent(UpdateIntent.Later)
        assertFalse(vm.state.value.showBanner)
        assertIs<UpdateStatus.Ready>(vm.state.value.status, "설정 화면에는 계속 준비됨으로 보인다")

        vm.onIntent(UpdateIntent.InstallNow)
        assertEquals("install /data/updates/ADB-Mirror-0.9.0.bin", updates.calls.last())
        assertEquals(UpdateEffect.Quit, vm.effects.first())
    }

    @Test
    fun autoCheckFailureIsSilentButManualShowsIt() = runTest {
        val updates = FakeUpdates(InstallKind.MacApp).apply { failCheck = "offline" }
        val vm = vm(updates, auto = true)
        assertEquals(UpdateStatus.Idle, vm.state.value.status)
        vm.onIntent(UpdateIntent.Check)
        assertEquals(UpdateStatus.Failed("업데이트를 확인하지 못했습니다: offline"), vm.state.value.status)
    }

    @Test
    fun manualCheckOffersDownloadAndReportsUpToDate() = runTest {
        val updates = FakeUpdates(InstallKind.WindowsMsi)
        val vm = vm(updates, auto = false)
        vm.onIntent(UpdateIntent.Check)
        assertEquals(UpdateStatus.Available(v090), vm.state.value.status, "수동 확인은 내려받기를 묻는다")
        vm.onIntent(UpdateIntent.Download)
        assertIs<UpdateStatus.Ready>(vm.state.value.status)

        val latest = FakeUpdates(InstallKind.WindowsMsi).apply { latest = UpdateInfo(current, "n") }
        val upToDate = vm(latest, auto = false)
        upToDate.onIntent(UpdateIntent.Check)
        assertEquals(UpdateStatus.UpToDate, upToDate.state.value.status)
    }

    @Test
    fun linuxPackageIsDownloadedButInstalledByUser() = runTest {
        val updates = FakeUpdates(InstallKind.LinuxDeb)
        val vm = vm(updates, auto = true)
        val status = assertIs<UpdateStatus.ManualInstall>(vm.state.value.status)
        assertEquals("sudo apt install '/data/updates/ADB-Mirror-0.9.0.bin'", status.command)
        vm.onIntent(UpdateIntent.InstallNow) // Ready가 아니라 무시
        assertFalse(updates.calls.any { it.startsWith("install") })
        vm.onIntent(UpdateIntent.OpenFolder)
        assertEquals(UpdateEffect.OpenFile("/data/updates"), vm.effects.first())
    }

    @Test
    fun devRunCanCheckButNotDownload() = runTest {
        val updates = FakeUpdates(InstallKind.Unsupported)
        val vm = vm(updates, auto = true)
        assertEquals(UpdateStatus.Available(v090), vm.state.value.status)
        vm.onIntent(UpdateIntent.Download)
        assertEquals(listOf("check"), updates.calls)
        vm.onIntent(UpdateIntent.OpenNotes)
        assertEquals(UpdateEffect.OpenUrl("https://example/notes"), vm.effects.first())
    }

    @Test
    fun downloadFailureIsShownOnManualAndKeepsOfferOnAuto() = runTest {
        val auto = vm(FakeUpdates(InstallKind.MacApp).apply { failDownload = "net" }, auto = true)
        assertEquals(UpdateStatus.Available(v090), auto.state.value.status)
        auto.onIntent(UpdateIntent.Download)
        assertEquals(UpdateStatus.Failed("내려받지 못했습니다: net"), auto.state.value.status)
    }
}
