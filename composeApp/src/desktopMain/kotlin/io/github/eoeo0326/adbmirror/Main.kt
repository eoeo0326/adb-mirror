package io.github.eoeo0326.adbmirror

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.layout.padding
import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.data.device.DeviceRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.mirror.MirrorRepositoryImpl
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ClasspathServerJarSource
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.data.settings.InMemorySettingsRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SendTouchUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StopMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.UpdateSettingsUseCase
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorIntent
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorRoute
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

fun main() {
    val transport = AdbBinaryTransport.locate()
    val viewModel = transport?.let(::createViewModel)
    // 창을 닫지 않고 종료 신호(SIGTERM·Ctrl+C)로 끝나도 서버·forward를 정리한다.
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { withTimeoutOrNull(3_000) { viewModel?.shutdown() } } })
    application {
        Window(
            onCloseRequest = {
                // 서버·forward를 정리할 때까지 잠깐 기다린 뒤 끝낸다.
                runBlocking { withTimeoutOrNull(3_000) { viewModel?.shutdown() } }
                exitApplication()
            },
            title = "ADB Mirror",
            state = rememberWindowState(size = DpSize(420.dp, 860.dp)),
        ) {
            AppTheme {
                if (viewModel != null) {
                    DevAutoConnect(viewModel)
                    MirrorRoute(viewModel)
                } else {
                    Text(
                        "adb를 찾을 수 없습니다. Android SDK platform-tools를 설치하거나 PATH·ANDROID_HOME을 확인해 주세요.",
                        Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * 개발·측정용: `ADB_MIRROR_DEV_CONNECT=<serial>`이면 그 기기가 목록에 뜨는 즉시 선택·연결 Intent를 보낸다.
 * 사용자가 쓰는 흐름(기기 선택 필수)은 바꾸지 않는다.
 */
@Composable
private fun DevAutoConnect(viewModel: MirrorViewModel) {
    val serial = remember { System.getenv("ADB_MIRROR_DEV_CONNECT")?.takeIf { it.isNotBlank() } } ?: return
    LaunchedEffect(serial) {
        viewModel.state.first { s -> s.devices.any { it.serial == serial } }
        viewModel.onIntent(MirrorIntent.SelectDevice(serial))
        viewModel.onIntent(MirrorIntent.Connect)
    }
}

/** 수동 DI. Koin은 화면·UseCase가 늘어나는 #9 이후에 도입한다. */
private fun createViewModel(transport: AdbBinaryTransport): MirrorViewModel {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = InMemorySettingsRepository()
    val devices = DeviceRepositoryImpl(transport)
    val mirror = MirrorRepositoryImpl(ScrcpyServerLauncher(transport, ClasspathServerJarSource, log = ::println), scope)
    return MirrorViewModel(
        getDevices = GetDevicesUseCase(devices),
        getSettings = GetSettingsUseCase(settings),
        startMirroring = StartMirroringUseCase(mirror, settings),
        stopMirroring = StopMirroringUseCase(),
        sendTouch = SendTouchUseCase(settings),
        updateSettings = UpdateSettingsUseCase(settings),
    )
}
