package io.github.eoeo0326.adbmirror.feature.devices

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessEndpoint
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
import io.github.eoeo0326.adbmirror.core.domain.repository.DeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDiscoveryRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DisconnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DiscoverWirelessServicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetKnownWirelessDevicesUseCase
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
    private var known = listOf<WirelessEndpoint>()
    private val services = MutableStateFlow<List<WirelessService>>(emptyList())
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
        override suspend fun known() = known
    }
    private val discovery = object : WirelessDiscoveryRepository {
        override fun services() = services
    }

    private fun vm(withWireless: Boolean = true, withDiscovery: Boolean = false) = DeviceListViewModel(
        GetDevicesUseCase(devices),
        if (withWireless) {
            WirelessActions(
                PairDeviceUseCase(wireless),
                ConnectWirelessDeviceUseCase(wireless),
                DisconnectWirelessDeviceUseCase(wireless),
                GetKnownWirelessDevicesUseCase(wireless),
                if (withDiscovery) DiscoverWirelessServicesUseCase(discovery) else null,
            )
        } else {
            null
        },
    )

    private fun pairing(host: String, port: Int) = WirelessService(WirelessService.Kind.Pairing, "adb-X-p", host, port)
    private fun connect(host: String, port: Int) = WirelessService(WirelessService.Kind.Connect, "adb-X", host, port)

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

    @Test
    fun foundPairingServiceFillsEmptyPairingFields() = runTest {
        val vm = vm(withDiscovery = true)
        services.value = listOf(connect("10.0.0.7", 40000), connect("192.168.0.12", 36331), pairing("192.168.0.12", 41234))
        val form = vm.state.value.wireless!!
        assertEquals(WirelessForm(host = "192.168.0.12", pairPort = "41234", found = services.value), form, "연결 포트는 채우지 않는다")

        // 사용자가 입력한 칸은 덮어쓰지 않고, 다른 주소의 서비스로는 채우지 않는다.
        vm.onIntent(DeviceListIntent.EditWireless(WirelessForm(host = "10.0.0.9")))
        services.value = listOf(pairing("192.168.0.12", 41235))
        assertEquals("", vm.state.value.wireless!!.pairPort)
        assertEquals(services.value, vm.state.value.wireless!!.found, "입력을 고쳐도 찾은 목록은 남는다")
    }

    @Test
    fun severalPairingCandidatesAreNotAutofilled() = runTest {
        val vm = vm(withDiscovery = true)
        services.value = listOf(pairing("10.0.0.7", 40000), pairing("192.168.0.12", 41234))
        assertEquals("", vm.state.value.wireless!!.pairPort)
        vm.onIntent(DeviceListIntent.EditWireless(vm.state.value.wireless!!.copy(host = "192.168.0.12")))
        services.value = services.value.reversed()
        assertEquals("41234", vm.state.value.wireless!!.pairPort, "주소를 정하면 그 주소의 서비스로 채운다")
    }

    @Test
    fun choosingConnectServiceConnects() = runTest {
        val vm = vm(withDiscovery = true)
        vm.onIntent(DeviceListIntent.UseService(connect("192.168.0.12", 36331)))
        assertEquals(listOf("connect 192.168.0.12:36331"), calls)
    }

    @Test
    fun knownDeviceReconnectsAtStartAndWhenItsPortIsFound() = runTest {
        known = listOf(WirelessEndpoint("192.168.0.12", 36331), WirelessEndpoint("10.0.0.9", 40000))
        failConnect = "꺼져 있음"
        vm(withDiscovery = true)
        assertEquals(emptyList(), calls, "기억한 포트로 시도했지만 실패(알리지 않음)")

        failConnect = null
        services.value = listOf(connect("192.168.0.12", 45009)) // 무선 디버깅을 다시 켜 포트가 바뀜
        assertEquals(listOf("connect 192.168.0.12:45009"), calls)
        services.value = listOf(connect("192.168.0.12", 45009), pairing("192.168.0.12", 41234))
        assertEquals(listOf("connect 192.168.0.12:45009"), calls, "이미 연결된 기기는 다시 시도하지 않는다")
    }
}
