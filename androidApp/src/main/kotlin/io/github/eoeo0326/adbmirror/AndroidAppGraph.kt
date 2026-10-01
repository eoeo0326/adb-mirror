package io.github.eoeo0326.adbmirror

import android.content.Context
import io.github.eoeo0326.adbmirror.core.adb.KadbKeyStore
import io.github.eoeo0326.adbmirror.core.adb.KadbTransport
import io.github.eoeo0326.adbmirror.core.data.conversion.AnimationConverter
import io.github.eoeo0326.adbmirror.core.data.conversion.BitmapWebpFrameEncoder
import io.github.eoeo0326.adbmirror.core.data.conversion.MediaCodecVideoFrameSource
import io.github.eoeo0326.adbmirror.core.data.device.DeviceRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.device.NsdWirelessDiscovery
import io.github.eoeo0326.adbmirror.core.data.device.SharedWirelessDiscovery
import io.github.eoeo0326.adbmirror.core.data.device.WirelessDeviceRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.mirror.MirrorRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.recording.FileRecordingRepository
import io.github.eoeo0326.adbmirror.core.data.recording.MediaStoreCaptureOutput
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ClasspathServerJarSource
import io.github.eoeo0326.adbmirror.core.data.scrcpy.LaunchedServers
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.data.screenshot.AndroidScreenshotSink
import io.github.eoeo0326.adbmirror.core.data.screenshot.ScreenshotRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.settings.InMemorySettingsRepository
import io.github.eoeo0326.adbmirror.core.data.storage.FileTextStore
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.usecase.CaptureScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConvertRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.CopyScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DisconnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DiscoverWirelessServicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetConversionFormatsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetKnownWirelessDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetVideoInfoUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.PairDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SaveScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SendTouchUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SetShowTouchesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.UpdateSettingsUseCase
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListViewModel
import io.github.eoeo0326.adbmirror.feature.devices.WirelessActions
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Android 앱의 수동 DI. 무선 디버깅(Kadb)으로 기기에 붙는다.
 * [privateDir]는 백업되지 않는 앱 저장소(adb 키·남은 서버 기록·연결했던 기기).
 */
class AndroidAppGraph(context: Context, privateDir: File) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = KadbTransport(KadbKeyStore(File(privateDir, "adb")))
    private val settings = InMemorySettingsRepository()
    private val devices = DeviceRepositoryImpl(transport)
    private val wireless = WirelessDeviceRepositoryImpl(transport, FileTextStore(File(privateDir, "known-devices.txt")))
    private val discovery = SharedWirelessDiscovery(NsdWirelessDiscovery(context), scope)
    val notificationPairing = NotificationPairing(context, scope, discovery, PairDeviceUseCase(wireless), ConnectWirelessDeviceUseCase(wireless))
    private val screenshots = ScreenshotRepositoryImpl(transport, AndroidScreenshotSink(context, "${context.packageName}.files"))
    private val recordings = FileRecordingRepository(
        scope,
        MediaStoreCaptureOutput(context),
        AnimationConverter(MediaCodecVideoFrameSource(), BitmapWebpFrameEncoder),
    )
    private val launched = LaunchedServers(FileTextStore(File(privateDir, "launched-servers.txt")))
    private val mirror = MirrorRepositoryImpl(
        ScrcpyServerLauncher(transport, ClasspathServerJarSource, log = { android.util.Log.i("adb-mirror", it) }, launched = launched),
        scope,
    )

    fun deviceListViewModel() = DeviceListViewModel(
        GetDevicesUseCase(devices),
        WirelessActions(
            PairDeviceUseCase(wireless),
            ConnectWirelessDeviceUseCase(wireless),
            DisconnectWirelessDeviceUseCase(wireless),
            GetKnownWirelessDevicesUseCase(wireless),
            DiscoverWirelessServicesUseCase(discovery),
            notificationPairing = true,
        ),
    )

    fun mirrorViewModel(device: Device) = MirrorViewModel(
        device = device,
        getSettings = GetSettingsUseCase(settings),
        startMirroring = StartMirroringUseCase(mirror, settings),
        stopMirroring = StopMirroringUseCase(),
        sendTouch = SendTouchUseCase(settings),
        updateSettings = UpdateSettingsUseCase(settings),
        setShowTouches = SetShowTouchesUseCase(devices),
        captureScreenshot = CaptureScreenshotUseCase(screenshots),
        copyScreenshot = CopyScreenshotUseCase(screenshots),
        saveScreenshot = SaveScreenshotUseCase(screenshots, settings),
        startRecording = StartRecordingUseCase(recordings, settings),
        stopRecording = StopRecordingUseCase(recordings),
        getVideoInfo = GetVideoInfoUseCase(recordings),
        getConversionFormats = GetConversionFormatsUseCase(recordings),
        convertRecording = ConvertRecordingUseCase(recordings),
    )
}
