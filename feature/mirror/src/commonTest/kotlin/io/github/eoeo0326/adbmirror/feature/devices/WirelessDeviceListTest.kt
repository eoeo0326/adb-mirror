package io.github.eoeo0326.adbmirror.feature.devices

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.repository.DeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DisconnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.PairDeviceUseCase
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WirelessDeviceListTest {
    private val list = MutableStateFlow<List<Device>>(emptyList())
    private val calls = mutableListOf<String>()
    private var failConnect: String? = null
    private var pairGate: CompletableDeferred<Unit>? = null
    private val devices = object : DeviceRepository {
        override fun devices() = list
        override suspend fun setShowTouches(serial: String, enabled: Boolean) = false
    }
    private val wireless = object : WirelessDeviceRepository {
        override suspend fun pair(host: String, port: Int, code: String) {
            pairGate?.await()
            calls += "pair $host:$port $code"
        }
        override suspend fun connect(host: String, port: Int): Device {
            failConnect?.let { error(it) }
            val d = Device("$host:$port", DeviceState.Online, "SM-N976N")
            list.update { it + d }
            calls += "connect $host:$port"
            return d
        }
        override suspend fun disconnect(serial: String) {
            list.update { l -> l.filterNot { it.serial == serial } }
            calls += "disconnect $serial"
        }
    }

    private fun vm(withWireless: Boolean = true) = DeviceListViewModel(
        GetDevicesUseCase(devices),
        if (withWireless) WirelessActions(PairDeviceUseCase(wireless), ConnectWirelessDeviceUseCase(wireless), DisconnectWirelessDeviceUseCase(wireless)) else null,
    )

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun formOnlyWhenPlatformSupportsWireless() {
        assertNull(vm(withWireless = false).state.value.wireless)
        assertEquals(WirelessForm(), vm().state.value.wireless)
    }

    @Test
    fun pairThenConnectAddsDeviceToList() = runTest {
        val vm = vm()
        vm.onIntent(DeviceListIntent.EditWireless(WirelessForm(host = "192.168.0.12", pairPort = "43541", code = "583079", connectPort = "43025")))
        vm.onIntent(DeviceListIntent.Pair)
        assertEquals("페어링했습니다. 이제 연결 포트로 연결하세요", vm.state.value.wireless!!.message)
        vm.onIntent(DeviceListIntent.ConnectWireless)
        val form = vm.state.value.wireless!!
        assertEquals("SM-N976N에 연결했습니다", form.message)
        assertFalse(form.failed)
        assertEquals(listOf("192.168.0.12:43025"), vm.state.value.devices.map { it.serial })
        assertEquals(listOf("pair 192.168.0.12:43541 583079", "connect 192.168.0.12:43025"), calls)
    }

    @Test
    fun invalidInputAndFailureAreShownUnderForm() = runTest {
        val vm = vm()
        vm.onIntent(DeviceListIntent.EditWireless(WirelessForm(host = "192.168.0.12", pairPort = "43541", code = "12")))
        vm.onIntent(DeviceListIntent.Pair)
        assertEquals("페어링 코드는 6자리 숫자입니다", vm.state.value.wireless!!.message)
        assertTrue(vm.state.value.wireless!!.failed)

        failConnect = "연결하지 못했습니다"
        vm.onIntent(DeviceListIntent.EditWireless(vm.state.value.wireless!!.copy(connectPort = "43025")))
        assertNull(vm.state.value.wireless!!.message, "입력을 고치면 지난 문구를 지운다")
        vm.onIntent(DeviceListIntent.ConnectWireless)
        assertEquals("연결하지 못했습니다", vm.state.value.wireless!!.message)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun oneActionAtATimeAndInputLockedWhileBusy() = runTest {
        val gate = CompletableDeferred<Unit>().also { pairGate = it }
        val vm = vm()
        vm.onIntent(DeviceListIntent.EditWireless(WirelessForm(host = "h", pairPort = "1", code = "123456", connectPort = "2")))
        vm.onIntent(DeviceListIntent.Pair)
        assertTrue(vm.state.value.wireless!!.busy)
        vm.onIntent(DeviceListIntent.ConnectWireless) // 무시
        vm.onIntent(DeviceListIntent.EditWireless(WirelessForm(host = "other"))) // 무시
        assertEquals("h", vm.state.value.wireless!!.host)
        gate.complete(Unit)
        assertFalse(vm.state.value.wireless!!.busy)
        assertEquals(listOf("pair h:1 123456"), calls)
    }

    @Test
    fun disconnectRemovesDevice() = runTest {
        val vm = vm()
        vm.onIntent(DeviceListIntent.EditWireless(WirelessForm(host = "h", connectPort = "2")))
        vm.onIntent(DeviceListIntent.ConnectWireless)
        vm.onIntent(DeviceListIntent.Disconnect("h:2"))
        assertTrue(vm.state.value.devices.isEmpty())
        assertEquals("disconnect h:2", calls.last())
    }
}
