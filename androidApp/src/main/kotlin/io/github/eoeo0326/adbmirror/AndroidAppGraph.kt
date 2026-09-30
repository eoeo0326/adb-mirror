package io.github.eoeo0326.adbmirror

import io.github.eoeo0326.adbmirror.core.adb.KadbKeyStore
import io.github.eoeo0326.adbmirror.core.adb.KadbTransport
import io.github.eoeo0326.adbmirror.core.data.device.DeviceRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.device.WirelessDeviceRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.mirror.MirrorRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ClasspathServerJarSource
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.data.screenshot.ScreenshotRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.screenshot.ScreenshotSink
import io.github.eoeo0326.adbmirror.core.data.settings.InMemorySettingsRepository
import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.Recording
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import io.github.eoeo0326.adbmirror.core.domain.repository.RecordingRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.CaptureScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConvertRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.CopyScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DisconnectWirelessDeviceUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetConversionFormatsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Android 앱의 수동 DI. 무선 디버깅(Kadb)으로 기기에 붙는다. */
class AndroidAppGraph(filesDir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = KadbTransport(KadbKeyStore(File(filesDir, "adb")))
    private val settings = InMemorySettingsRepository()
    private val devices = DeviceRepositoryImpl(transport)
    private val wireless = WirelessDeviceRepositoryImpl(transport)
    private val screenshots = ScreenshotRepositoryImpl(transport, UnsupportedScreenshotSink)
    private val recordings = UnsupportedRecordingRepository
    private val mirror = MirrorRepositoryImpl(ScrcpyServerLauncher(transport, ClasspathServerJarSource, log = { android.util.Log.i("adb-mirror", it) }), scope)

    fun deviceListViewModel() = DeviceListViewModel(
        GetDevicesUseCase(devices),
        WirelessActions(PairDeviceUseCase(wireless), ConnectWirelessDeviceUseCase(wireless), DisconnectWirelessDeviceUseCase(wireless)),
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

private const val NOT_YET = "Android 앱에서는 아직 지원하지 않습니다"

/** 스크린샷 복사·저장(클립보드·MediaStore)은 뒤 작업에서 붙인다. */
private object UnsupportedScreenshotSink : ScreenshotSink {
    override suspend fun copyToClipboard(png: ByteArray) = throw UnsupportedOperationException(NOT_YET)
    override suspend fun save(png: ByteArray, dir: String?, baseName: String): String = throw UnsupportedOperationException(NOT_YET)
}

/** 녹화·변환은 뒤 작업에서 붙인다. */
private object UnsupportedRecordingRepository : RecordingRepository {
    override suspend fun start(session: MirrorSession, outputDir: String?) = throw UnsupportedOperationException(NOT_YET)
    override suspend fun stop(serial: String): Recording? = null
    override suspend fun info(file: String): VideoInfo = throw UnsupportedOperationException(NOT_YET)
    override fun supportedFormats(): Set<AnimatedFormat> = emptySet()
    override fun convert(files: List<String>, options: ConversionOptions): Flow<ConversionProgress> = flow { throw UnsupportedOperationException(NOT_YET) }
}
