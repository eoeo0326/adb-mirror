package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.feature.mirror.video.VideoSurface
import io.github.eoeo0326.adbmirror.feature.mirror.video.touchEffect

/** ViewModel에 연결된 미러링 창 내용. */
@Composable
fun MirrorRoute(viewModel: MirrorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val session by viewModel.session.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect -> effect.message()?.let { snackbar.showSnackbar(it) } }
    }
    Box(modifier) {
        MirrorScreen(state, session, viewModel::onIntent)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

/** 창 안에 잠깐 띄울 문구. 따로 다루는 이펙트(녹화 전 확인 등)는 null. */
fun MirrorEffect.message(): String? = when (this) {
    is MirrorEffect.ShowMessage -> message
    is MirrorEffect.Error -> message
    is MirrorEffect.ScreenshotSaved -> "스크린샷을 저장했습니다: $path"
    is MirrorEffect.RecordingSaved -> "녹화를 저장했습니다: ${files.joinToString()}"
    is MirrorEffect.ConversionDone -> "변환을 마쳤습니다: $file"
    MirrorEffect.AskShowTouchesForRecording -> null
}

@Composable
fun MirrorScreen(state: MirrorState, session: MirrorSession?, onIntent: (MirrorIntent) -> Unit, modifier: Modifier = Modifier) {
    val mirroring = state.connection as? Connection.Mirroring
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mirroring?.deviceName ?: state.device.model ?: state.device.serial, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (session != null) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { onIntent(MirrorIntent.Disconnect) }) { Text("연결 끊기") }
            }
            Spacer(Modifier.width(4.dp))
            OptionsMenu(state, onIntent)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val c = state.connection) {
                is Connection.Mirroring -> if (session != null) {
                    VideoSurface(
                        session = session,
                        videoSize = c.videoSize,
                        onTouch = { action, x, y -> onIntent(MirrorIntent.Touch(action, x, y)) },
                        modifier = Modifier.fillMaxSize()
                            .touchEffect(enabled = state.settings.touchEffect && !state.settings.viewOnly, videoSize = c.videoSize),
                    )
                }
                Connection.Connecting -> Text("연결 중…")
                is Connection.Error -> Disconnected("연결이 끊겼습니다: ${c.message}", onIntent)
                Connection.Idle -> Disconnected("연결을 끊었습니다", onIntent)
            }
        }
    }
}

/** 창이 폰 폭에 맞춰 좁아지므로 동작·토글은 툴바 대신 메뉴에 둔다. Desktop은 메뉴 막대·단축키로도 부른다. */
@Composable
private fun OptionsMenu(state: MirrorState, onIntent: (MirrorIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("메뉴") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("스크린샷 복사", style = MaterialTheme.typography.bodyMedium) },
                enabled = !state.capturingScreenshot,
                onClick = { open = false; onIntent(MirrorIntent.CopyScreenshot) },
            )
            DropdownMenuItem(
                text = { Text("스크린샷 저장", style = MaterialTheme.typography.bodyMedium) },
                enabled = !state.capturingScreenshot,
                onClick = { open = false; onIntent(MirrorIntent.SaveScreenshot) },
            )
            HorizontalDivider()
            MenuToggle("보기 전용", state.settings.viewOnly) { onIntent(MirrorIntent.ToggleViewOnly) }
            MenuToggle("클릭 이펙트", state.settings.touchEffect) { onIntent(MirrorIntent.ToggleTouchEffect) }
            MenuToggle("기기에 터치 표시", state.settings.showTouches) { onIntent(MirrorIntent.ToggleShowTouches) }
        }
    }
}

@Composable
private fun MenuToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onToggle,
    )
}

@Composable
private fun Disconnected(message: String, onIntent: (MirrorIntent) -> Unit) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, textAlign = TextAlign.Center)
        Button(onClick = { onIntent(MirrorIntent.Connect) }) { Text("다시 연결") }
    }
}
