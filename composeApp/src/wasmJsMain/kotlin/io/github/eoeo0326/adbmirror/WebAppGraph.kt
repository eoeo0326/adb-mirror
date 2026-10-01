package io.github.eoeo0326.adbmirror

import io.github.eoeo0326.adbmirror.core.adb.protocol.AdbConnection
import io.github.eoeo0326.adbmirror.core.adb.protocol.DirectAdbTransport
import io.github.eoeo0326.adbmirror.core.adb.web.UsbDevice
import io.github.eoeo0326.adbmirror.core.adb.web.WebAdbKeyStore
import io.github.eoeo0326.adbmirror.core.adb.web.WebUsbAdbChannel
import io.github.eoeo0326.adbmirror.core.data.device.DeviceRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.mirror.MirrorRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.scrcpy.LaunchedServers
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.data.screenshot.ScreenshotRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.settings.InMemorySettingsRepository
import io.github.eoeo0326.adbmirror.core.data.web.FetchServerJarSource
import io.github.eoeo0326.adbmirror.core.data.web.LocalStorageTextStore
import io.github.eoeo0326.adbmirror.core.data.web.WebRecordingRepository
import io.github.eoeo0326.adbmirror.core.data.web.WebScreenshotSink
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.usecase.CaptureScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConvertRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.CopyScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetConversionFormatsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetVideoInfoUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SaveScreenshotUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SendTouchUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SetShowTouchesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.UpdateSettingsUseCase
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListViewModel
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

/** 웹앱의 수동 DI. WebUSB로 고른 기기에 ADB 프로토콜로 직접 붙는다(adb 서버 없음). */
class WebAppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = DirectAdbTransport(scope)
    private val settings = InMemorySettingsRepository()
    private val devices = DeviceRepositoryImpl(transport)
    private val screenshots = ScreenshotRepositoryImpl(transport, WebScreenshotSink)
    private val recordings = WebRecordingRepository(scope)
    private val launched = LaunchedServers(LocalStorageTextStore("adb-mirror.launched-servers"))
    private val mirror = MirrorRepositoryImpl(ScrcpyServerLauncher(transport, FetchServerJarSource, log = ::println, launched = launched), scope)

    /**
     * 고른 USB 기기에 붙는다. 이 브라우저의 키를 처음 보는 기기면 "USB 디버깅을 허용하시겠습니까?"가 뜨고
     * [onWaitingForUser]가 불린다. 허용을 기다리는 시간은 1분이다.
     */
    suspend fun connect(device: UsbDevice, onWaitingForUser: () -> Unit): String {
        // 같은 기기를 다시 고르면 이전 연결을 먼저 닫아 인터페이스를 놓게 한다.
        transport.remove(WebUsbAdbChannel.serialOf(device))
        val channel = WebUsbAdbChannel.open(device)
        try {
            val key = WebAdbKeyStore.loadOrCreate()
            var waitingForUser = false
            val connection = withTimeoutOrNull(60_000) {
                AdbConnection.connect(
                    channel,
                    key,
                    WebAdbKeyStore.KEY_NAME,
                    scope,
                    onWaitingForUser = {
                        waitingForUser = true
                        onWaitingForUser()
                    },
                    log = { println("[adb-mirror] $it") },
                )
            } ?: throw IllegalStateException(
                if (waitingForUser) {
                    "기기가 1분 안에 허용하지 않았습니다. 폰 화면을 켜고 잠금을 푼 뒤 다시 연결하세요. 창이 계속 안 뜨면 개발자 옵션에서 \"USB 디버깅 권한 승인 취소\" 후 USB 디버깅을 껐다 켜 보세요"
                } else {
                    "기기가 응답하지 않습니다. 케이블을 다시 꽂고, 다른 프로그램(adb, Android Studio)이 기기를 쓰고 있지 않은지 확인하세요"
                },
            )
            transport.add(channel.serial, connection)
            return connection.model ?: channel.productName ?: channel.serial
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    fun deviceListViewModel() = DeviceListViewModel(GetDevicesUseCase(devices))

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
