package io.github.eoeo0326.adbmirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.data.screenshot.DesktopScreenshotSink
import io.github.eoeo0326.adbmirror.core.data.settings.PropertiesSettingsRepository
import io.github.eoeo0326.adbmirror.core.data.settings.SettingsLocation
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListEffect
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListIntent
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListRoute
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListViewModel
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorRoute
import io.github.eoeo0326.adbmirror.feature.settings.SettingsPlatform
import io.github.eoeo0326.adbmirror.feature.settings.SettingsRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    val location = SettingsLocation.resolve()
    val settings = PropertiesSettingsRepository(location.settingsFile)
    val transport = AdbBinaryTransport.locate(settings.settings.value.adbPath)
    val graph = transport?.let { AppGraph(it, settings) }
    val platform = SettingsPlatform(
        storageDescription = "설정 파일: ${location.settingsFile.path}" + if (location.portable) " (포터블)" else "",
        defaultOutputDescription = "기본 위치(${DesktopScreenshotSink().defaultDir().path})",
        pickFolder = { pickFolder("저장 폴더 선택") },
        pickAdb = { pickFile("adb 실행 파일 선택") },
    )
    // 창을 닫지 않고 종료 신호(SIGTERM·Ctrl+C)로 끝나도 서버·forward를 정리한다.
    Runtime.getRuntime().addShutdownHook(Thread { closeAllBlocking() })
    application {
        if (graph == null) {
            AdbMissingWindow(settings)
        } else {
            DesktopApp(graph, platform, WindowBoundsStore(File(location.dir, "windows.properties")))
        }
    }
}

@Composable
private fun ApplicationScope.DesktopApp(graph: AppGraph, platform: SettingsPlatform, windowBounds: WindowBoundsStore) {
    val listViewModel = remember { graph.deviceListViewModel() }
    val settingsViewModel = remember { graph.settingsViewModel() }
    var settingsOpen by remember { mutableStateOf(false) }
    val settingsFocus = remember { mutableIntStateOf(0) }
    val openSettings: () -> Unit = {
        settingsOpen = true
        settingsFocus.intValue++
    }
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
                        val state = restoredWindowState(windowBounds, "mirror.$serial", DpSize(420.dp, 860.dp), rememberSize = false)
                        openWindows[serial] = MirrorWindowHolder(effect.device, graph, state)
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
        if (isMac && Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler { _, response ->
                    closeAllBlocking()
                    response.performQuit()
                }
            }
            // 앱 메뉴의 "설정…"(⌘,)
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler { openSettings() }
        }
    }

    val listWindowState = remember { restoredWindowState(windowBounds, "list", DpSize(420.dp, 560.dp), rememberSize = true) }
    Window(onCloseRequest = quit, title = "ADB Mirror", state = listWindowState) {
        RememberWindowBounds(listWindowState, windowBounds, "list", rememberSize = true)
        DeviceListMenuBar(onOpenSettings = openSettings, onQuit = quit)
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
                RememberWindowBounds(holder.windowState, windowBounds, "mirror.$serial", rememberSize = false)
                MirrorMenuBar(holder, onCloseWindow = closeWindow, onOpenSettings = openSettings, onQuit = quit)
                FitWindowToVideo(holder)
                AppTheme { MirrorRoute(holder.viewModel) }
            }
        }
    }

    if (settingsOpen) {
        Window(onCloseRequest = { settingsOpen = false }, title = "ADB Mirror 설정", state = rememberWindowState(size = DpSize(460.dp, 700.dp))) {
            val focus by settingsFocus
            LaunchedEffect(focus) { window.toFront() }
            AppTheme { SettingsRoute(settingsViewModel, platform) }
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
private fun ApplicationScope.AdbMissingWindow(settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val current by settings.settings.collectAsState(initial = null)
    var saved by remember { mutableStateOf(false) }
    Window(onCloseRequest = ::exitApplication, title = "ADB Mirror") {
        AppTheme {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "adb를 찾을 수 없습니다. Android SDK platform-tools를 설치하거나 PATH·ANDROID_HOME을 확인해 주세요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                current?.adbPath?.let { Text("설정된 adb 경로: $it", style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    pickFile("adb 실행 파일 선택")?.let { path ->
                        scope.launch {
                            settings.update { it.copy(adbPath = path) }
                            saved = true
                        }
                    }
                }) { Text("adb 위치 지정…") }
                if (saved) Text("저장했습니다. 앱을 다시 시작하면 적용됩니다.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
