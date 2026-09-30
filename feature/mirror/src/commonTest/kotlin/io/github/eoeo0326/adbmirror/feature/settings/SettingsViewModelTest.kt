package io.github.eoeo0326.adbmirror.feature.settings

import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val settings = MutableStateFlow(Settings())
    private val repo = object : SettingsRepository {
        override val settings = this@SettingsViewModelTest.settings
        override suspend fun update(transform: (Settings) -> Settings) = this@SettingsViewModelTest.settings.update(transform)
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun intentsUpdateRepositoryAndStateFollows() = runTest {
        val vm = SettingsViewModel(GetSettingsUseCase(repo), UpdateSettingsUseCase(repo))
        vm.onIntent(SettingsIntent.SetMaxSize(0))
        vm.onIntent(SettingsIntent.SetMaxFps(30))
        vm.onIntent(SettingsIntent.ToggleTouchEffect)
        vm.onIntent(SettingsIntent.SetOutputDir("/shots"))
        vm.onIntent(SettingsIntent.SetAdbPath("/sdk/adb"))
        val expected = Settings(maxSize = 0, maxFps = 30, touchEffect = false, outputDir = "/shots", adbPath = "/sdk/adb")
        assertEquals(expected, settings.value)
        assertEquals(expected, vm.state.value)

        vm.onIntent(SettingsIntent.SetOutputDir(null))
        vm.onIntent(SettingsIntent.SetAdbPath(null))
        assertEquals(null, vm.state.value.outputDir)
        assertEquals(null, vm.state.value.adbPath)
    }

    @Test
    fun reduceToggles() {
        val s = Settings()
        assertEquals(!s.viewOnly, SettingsViewModel.reduce(s, SettingsIntent.ToggleViewOnly).viewOnly)
        assertEquals(!s.showTouches, SettingsViewModel.reduce(s, SettingsIntent.ToggleShowTouches).showTouches)
    }
}
