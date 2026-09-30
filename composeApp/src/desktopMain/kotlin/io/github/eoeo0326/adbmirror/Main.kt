package io.github.eoeo0326.adbmirror

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListEffect
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListIntent
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListRoute
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListViewModel
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 열려 있는 미러링 창(serial → 창). 종료 훅에서도 접근한다. */
private val openWindows = mutableStateMapOf<String, MirrorWindowHolder>()

private fun closeAllBlocking() = runBlocking {
    withTimeoutOrNull(3_000) { openWindows.values.toList().forEach { it.close() } }
}

fun main() {
    val transport = AdbBinaryTransport.locate()
    val graph = transport?.let(::AppGraph)
    // 창을 닫지 않고 종료 신호(SIGTERM·Ctrl+C)로 끝나도 서버·forward를 정리한다.
    Runtime.getRuntime().addShutdownHook(Thread { closeAllBlocking() })
    application {
        if (graph == null) {
            AdbMissingWindow()
        } else {
            DesktopApp(graph)
        }
    }
}

@Composable
private fun ApplicationScope.DesktopApp(graph: AppGraph) {
    val listViewModel = remember { graph.deviceListViewModel() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        listViewModel.effects.collect { effect ->
            when (effect) {
                is DeviceListEffect.OpenMirror -> {
                    val existing = openWindows[effect.device.serial]
                    if (existing != null) existing.focusRequest.intValue++ else openWindows[effect.device.serial] = MirrorWindowHolder(effect.device, graph)
                }
            }
        }
    }
    DevAutoOpen(listViewModel)

    Window(
        onCloseRequest = {
            closeAllBlocking()
            exitApplication()
        },
        title = "ADB Mirror",
        state = rememberWindowState(size = DpSize(420.dp, 560.dp)),
    ) {
        AppTheme { DeviceListRoute(listViewModel) }
    }

    for ((serial, holder) in openWindows) {
        key(serial) {
            Window(
                onCloseRequest = {
                    openWindows.remove(serial)
                    listViewModel.onIntent(DeviceListIntent.MirrorClosed(serial))
                    scope.launch { holder.close() }
                },
                state = holder.windowState,
                title = "ADB Mirror — ${holder.device.model ?: serial}",
            ) {
                FitWindowToVideo(holder)
                AppTheme { MirrorRoute(holder.viewModel) }
            }
        }
    }
}

/**
 * 개발·측정용: `ADB_MIRROR_DEV_CONNECT=<serial>`이면 그 기기가 목록에 뜨는 즉시 선택·열기 Intent를 보낸다.
 * 사용자가 쓰는 흐름(기기 선택 필수)은 바꾸지 않는다.
 */
@Composable
private fun DevAutoOpen(viewModel: DeviceListViewModel) {
    val serial = remember { System.getenv("ADB_MIRROR_DEV_CONNECT")?.takeIf { it.isNotBlank() } } ?: return
    LaunchedEffect(serial) {
        viewModel.state.first { s -> s.devices.any { it.serial == serial } }
        viewModel.onIntent(DeviceListIntent.Select(serial))
        viewModel.onIntent(DeviceListIntent.Open)
    }
}

@Composable
private fun ApplicationScope.AdbMissingWindow() {
    Window(onCloseRequest = ::exitApplication, title = "ADB Mirror") {
        AppTheme {
            Text(
                "adb를 찾을 수 없습니다. Android SDK platform-tools를 설치하거나 PATH·ANDROID_HOME을 확인해 주세요.",
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
