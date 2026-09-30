package io.github.eoeo0326.adbmirror

import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.data.device.DeviceRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.mirror.MirrorRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ClasspathServerJarSource
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.data.settings.InMemorySettingsRepository
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SendTouchUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.UpdateSettingsUseCase
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListViewModel
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 수동 DI. 화면·UseCase가 더 늘면 Koin으로 옮긴다. */
class AppGraph(transport: AdbBinaryTransport) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = InMemorySettingsRepository()
    private val devices = DeviceRepositoryImpl(transport)
    private val mirror = MirrorRepositoryImpl(ScrcpyServerLauncher(transport, ClasspathServerJarSource, log = ::println), scope)

    fun deviceListViewModel() = DeviceListViewModel(GetDevicesUseCase(devices))

    fun mirrorViewModel(device: Device) = MirrorViewModel(
        device = device,
        getSettings = GetSettingsUseCase(settings),
        startMirroring = StartMirroringUseCase(mirror, settings),
        stopMirroring = StopMirroringUseCase(),
        sendTouch = SendTouchUseCase(settings),
        updateSettings = UpdateSettingsUseCase(settings),
    )
}
