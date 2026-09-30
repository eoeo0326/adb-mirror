package io.github.eoeo0326.adbmirror.core.data.device

import io.github.eoeo0326.adbmirror.core.adb.AdbDevice
import io.github.eoeo0326.adbmirror.core.data.mirror.FakeAdbTransport
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceRepositoryImplTest {
    @Test
    fun mapsAdbStates() = runTest {
        val transport = FakeAdbTransport()
        transport.deviceUpdates.emit(
            listOf(AdbDevice("A", "device", "SM N976N"), AdbDevice("B", "unauthorized"), AdbDevice("C", "offline"), AdbDevice("D", "recovery")),
        )
        val devices = DeviceRepositoryImpl(transport).devices().first()
        assertEquals(
            listOf(
                Device("A", DeviceState.Online, "SM N976N"),
                Device("B", DeviceState.Unauthorized),
                Device("C", DeviceState.Offline),
                Device("D", DeviceState.Offline),
            ),
            devices,
        )
    }

    @Test
    fun showTouchesReturnsPreviousAndWritesNewValue() = runTest {
        val transport = FakeAdbTransport().apply { shellReply = { if (it.contains("get")) "1\n" else "" } }
        val previous = DeviceRepositoryImpl(transport).setShowTouches("A", enabled = false)
        assertTrue(previous)
        assertEquals(listOf("settings", "put", "system", "show_touches", "0"), transport.shellCommands.last())
    }
}
