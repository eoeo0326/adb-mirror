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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import java.awt.Desktop
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 열려 있는 미러링 창(serial → 창). */
private val openWindows = mutableStateMapOf<String, MirrorWindowHolder>()

/** 창은 닫혔지만 세션 정리가 끝나지 않은 것(serial → 정리 작업). 종료 훅이 이것도 기다린다. */
private val closingWindows = ConcurrentHashMap<String, Job>()

private fun closeAllBlocking() = runBlocking {
    withTimeoutOrNull(3_000) {
        openWindows.values.toList().forEach { it.close() }
        closingWindows.values.toList().joinAll()
    }
}

fun main() {
    // AWT가 뜨기 전에 정해야 한다. macOS에서 메뉴 막대를 화면 위에 붙이고 앱 메뉴 이름을 정한다.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "ADB Mirror")
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
                    val serial = effect.device.serial
                    val existing = openWindows[serial]
                    if (existing != null) {
                        existing.focusRequest.intValue++
                    } else {
                        // 같은 기기의 이전 창을 정리하는 중이면 끝난 뒤에 연다(이전 세션과 새 세션이 겹치지 않게).
                        closingWindows[serial]?.join()
                        openWindows[serial] = MirrorWindowHolder(effect.device, graph)
                    }
                }
            }
        }
    }
    DevAutoOpen(listViewModel)

    val quit = {
        closeAllBlocking()
        exitApplication()
    }
    // macOS 앱 메뉴의 종료(⌘Q)도 창을 닫을 때와 같이 세션을 정리하고 끝낸다.
    LaunchedEffect(Unit) {
        if (isMac && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler { _, response ->
                closeAllBlocking()
                response.performQuit()
            }
        }
    }

    Window(onCloseRequest = quit, title = "ADB Mirror", state = rememberWindowState(size = DpSize(420.dp, 560.dp))) {
        DeviceListMenuBar(onQuit = quit)
        AppTheme { DeviceListRoute(listViewModel) }
    }

    for ((serial, holder) in openWindows) {
        key(serial) {
            val closeWindow = {
                if (openWindows.remove(serial) != null) {
                    listViewModel.onIntent(DeviceListIntent.MirrorClosed(serial))
                    val job = scope.launch { holder.close() }
                    closingWindows[serial] = job
                    job.invokeOnCompletion { closingWindows.remove(serial, job) }
                }
            }
            Window(onCloseRequest = closeWindow, state = holder.windowState, title = "ADB Mirror — ${holder.device.model ?: serial}") {
                MirrorMenuBar(holder, onCloseWindow = closeWindow, onQuit = quit)
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
