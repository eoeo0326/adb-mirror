package io.github.eoeo0326.adbmirror.feature.devices

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.repository.DeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListTest {
    private val online = Device("A", DeviceState.Online, "SM N976N")
    private val unauthorized = Device("B", DeviceState.Unauthorized)
    private fun DeviceListState.apply(vararg r: DeviceListResult) = r.fold(this) { s, x -> DeviceListReducer.reduce(s, x) }

    @Test
    fun singleDeviceIsNeverAutoSelected() {
        val s = DeviceListState().apply(DeviceListResult.DevicesLoaded(listOf(online)))
        assertNull(s.selectedSerial)
        assertFalse(s.canOpen)
    }

    @Test
    fun onlyOnlineDevicesCanBeSelected() {
        val base = DeviceListState().apply(DeviceListResult.DevicesLoaded(listOf(online, unauthorized)))
        assertNull(base.apply(DeviceListResult.Selected("B")).selectedSerial)
        assertNull(base.apply(DeviceListResult.Selected("missing")).selectedSerial)
        assertTrue(base.apply(DeviceListResult.Selected("A")).canOpen)
    }

    @Test
    fun selectionClearedWhenDeviceGoesAwayOrUnavailable() {
        val selected = DeviceListState().apply(DeviceListResult.DevicesLoaded(listOf(online)), DeviceListResult.Selected("A"))
        assertNull(selected.apply(DeviceListResult.DevicesLoaded(emptyList())).selectedSerial)
        assertNull(selected.apply(DeviceListResult.DevicesLoaded(listOf(Device("A", DeviceState.Offline)))).selectedSerial)
    }

    @Test
    fun tracksOpenWindows() {
        val s = DeviceListState().apply(DeviceListResult.Opened("A"), DeviceListResult.Opened("C"), DeviceListResult.Closed("A"))
        assertEquals(setOf("C"), s.openSerials)
    }

    private val devices = MutableStateFlow(listOf(online))
    private val repo = object : DeviceRepository {
        override fun devices() = devices
        override suspend fun setShowTouches(serial: String, enabled: Boolean) = false
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openRequiresSelectionThenEmitsEffect() = runTest {
        val vm = DeviceListViewModel(GetDevicesUseCase(repo))
        vm.onIntent(DeviceListIntent.Open) // 선택 전: 아무 일도 없음
        assertTrue(vm.state.value.openSerials.isEmpty())
        vm.onIntent(DeviceListIntent.Select("A"))
        vm.onIntent(DeviceListIntent.Open)
        assertEquals(DeviceListEffect.OpenMirror(online), vm.effects.first())
        assertEquals(setOf("A"), vm.state.value.openSerials)
        vm.onIntent(DeviceListIntent.MirrorClosed("A"))
        assertTrue(vm.state.value.openSerials.isEmpty())
    }
}
