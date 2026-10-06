package io.github.eoeo0326.adbmirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.eoeo0326.adbmirror.core.adb.web.requestAdbDevice
import io.github.eoeo0326.adbmirror.core.adb.web.webUsbSupported
import io.github.eoeo0326.adbmirror.core.data.web.fetchResource
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.feature.conversion.ConversionViewModel
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListEffect
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListIntent
import io.github.eoeo0326.adbmirror.feature.devices.DeviceListRoute
import io.github.eoeo0326.adbmirror.feature.mirror.ConversionHost
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorRoute
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorViewModel
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        // Compose Web에는 한글 글꼴이 없어 함께 배포한 Noto Sans KR(한글 음절·라틴만 남긴 서브셋)을 받은 뒤 그린다.
        var font by remember { mutableStateOf<FontFamily?>(null) }
        LaunchedEffect(Unit) {
            font = runCatching { FontFamily(Font("NotoSansKR", fetchResource("fonts/NotoSansKR-Regular.ttf"))) }.getOrDefault(FontFamily.Default)
        }
        val family = font ?: return@ComposeViewport
        AppTheme(family) { if (webUsbSupported()) WebApp(remember { WebAppGraph() }) else UnsupportedBrowser() }
    }
}

/** WebUSB가 없는 브라우저(Firefox·Safari 등) 안내. */
@Composable
private fun UnsupportedBrowser() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("이 브라우저는 WebUSB를 지원하지 않습니다", style = MaterialTheme.typography.titleMedium)
        Text(
            "Chrome·Edge 같은 Chromium 기반 브라우저에서 열어 주세요. https 또는 localhost 주소여야 합니다.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 480.dp),
        )
    }
}

/** 기기 하나의 미러링 화면. 화면을 떠날 때 세션을 정리한 뒤 ViewModel을 지운다. */
private class MirrorHolder(val device: Device, graph: WebAppGraph) {
    private val store = ViewModelStore()
    private val provider = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer { graph.mirrorViewModel(device) }
            initializer { graph.conversionViewModel() }
        },
    )
    val viewModel: MirrorViewModel = provider[MirrorViewModel::class]
    val conversion: ConversionViewModel = provider[ConversionViewModel::class]

    suspend fun close() {
        conversion.shutdown()
        viewModel.shutdown()
        store.clear()
    }
}

@Composable
private fun WebApp(graph: WebAppGraph) {
    val listViewModel = remember { graph.deviceListViewModel() }
    var mirror by remember { mutableStateOf<MirrorHolder?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(listViewModel) {
        listViewModel.effects.collect { effect ->
            when (effect) {
                is DeviceListEffect.OpenMirror -> if (mirror?.device?.serial != effect.device.serial) {
                    mirror?.let { old -> scope.launch { old.close() } }
                    mirror = MirrorHolder(effect.device, graph)
                }
                DeviceListEffect.StartNotificationPairing -> Unit
            }
        }
    }
    val current = mirror
    if (current != null) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = {
                mirror = null
                listViewModel.onIntent(DeviceListIntent.MirrorClosed(current.device.serial))
                scope.launch { current.close() }
            }) { Text("← 기기 목록") }
            // 웹은 결과를 다운로드로 내보내므로 열기 버튼이 없다.
            val conversion = remember(current) { ConversionHost.Overlay(current.conversion, opener = null) }
            MirrorRoute(current.viewModel, conversion, Modifier.fillMaxSize())
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    // requestDevice는 사용자 클릭 안에서 바로 불러야 해서 코루틴 밖에서 Promise를 받는다.
                    val chosen = requestAdbDevice()
                    busy = true
                    status = "연결하는 중…"
                    scope.launch {
                        status = try {
                            val name = graph.connect(chosen.await()) { status = "기기 화면에서 \"USB 디버깅 허용\"을 눌러 주세요" }
                            "${name}에 연결했습니다"
                        } catch (e: Throwable) {
                            "연결하지 못했습니다: ${readableError(e)}"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("USB 기기 연결") }
            Text(
                status ?: "USB 디버깅을 켠 기기를 케이블로 연결한 뒤 누르세요. 이 컴퓨터의 adb 서버는 꺼야 합니다(adb kill-server).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DeviceListRoute(listViewModel, Modifier.fillMaxSize().padding(top = 8.dp))
    }
}

/** JS 예외(DOMException)는 Kotlin 메시지에 형식 정보가 붙어 길어진다. 원문 설명만 보여 준다. */
private fun readableError(e: Throwable): String {
    val message = e.message ?: return e::class.simpleName ?: "알 수 없는 오류"
    return message.removePrefix("Non-Kotlin exception ").substringBefore(" of type '")
}
