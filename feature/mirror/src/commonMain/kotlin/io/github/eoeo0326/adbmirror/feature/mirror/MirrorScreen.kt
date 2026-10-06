package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.feature.mirror.video.VideoSurface
import io.github.eoeo0326.adbmirror.feature.mirror.video.touchEffect
import kotlinx.coroutines.delay

/** ViewModel에 연결된 미러링 창 내용. */
@Composable
fun MirrorRoute(viewModel: MirrorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val session by viewModel.session.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            val message = effect.message() ?: return@collect
            // 녹화를 저장하면 바로 변환할 수 있게 한다.
            val action = if (effect is MirrorEffect.RecordingSaved) "변환…" else null
            if (snackbar.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                (effect as? MirrorEffect.RecordingSaved)?.let { viewModel.onIntent(MirrorIntent.OpenConversion(it.files)) }
            }
        }
    }
    Box(modifier) {
        MirrorScreen(state, session, viewModel::onIntent)
        state.conversionDraft?.let { ConversionPanel(it, state.conversion, viewModel::onIntent) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

/** 창 안에 잠깐 띄울 문구. 따로 다루는 이펙트(녹화 전 확인 등)는 null. */
fun MirrorEffect.message(): String? = when (this) {
    is MirrorEffect.ShowMessage -> message
    is MirrorEffect.Error -> message
    is MirrorEffect.ScreenshotSaved -> "스크린샷을 저장했습니다: $path"
    is MirrorEffect.RecordingSaved -> "녹화를 저장했습니다: " + locations.first() + if (locations.size > 1) " 외 ${locations.size - 1}개(회전)" else ""
    is MirrorEffect.ConversionDone -> "변환을 마쳤습니다: $file"
    MirrorEffect.AskShowTouchesForRecording -> null
}

@Composable
fun MirrorScreen(state: MirrorState, session: MirrorSession?, onIntent: (MirrorIntent) -> Unit, modifier: Modifier = Modifier) {
    val mirroring = state.connection as? Connection.Mirroring
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mirroring?.deviceName ?: state.device.model ?: state.device.serial, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1)
            RecordingBadge(state.recording)
            RecordingStopButton(state.recording, onIntent)
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
            val rec = state.recording
            DropdownMenuItem(
                text = { Text(if (rec is RecordingState.Idle) "녹화 시작" else "녹화 정지", style = MaterialTheme.typography.bodyMedium) },
                enabled = (rec is RecordingState.Idle && state.connection is Connection.Mirroring) || rec is RecordingState.Recording,
                onClick = { open = false; onIntent(if (rec is RecordingState.Idle) MirrorIntent.StartRecording else MirrorIntent.StopRecording) },
            )
            DropdownMenuItem(
                text = { Text("최근 녹화 변환…", style = MaterialTheme.typography.bodyMedium) },
                enabled = state.lastRecording.isNotEmpty(),
                onClick = { open = false; onIntent(MirrorIntent.OpenConversion(state.lastRecording)) },
            )
            HorizontalDivider()
            MenuToggle("보기 전용", state.settings.viewOnly) { onIntent(MirrorIntent.ToggleViewOnly) }
            MenuToggle("클릭 이펙트", state.settings.touchEffect) { onIntent(MirrorIntent.ToggleTouchEffect) }
            MenuToggle("기기에 터치 표시", state.settings.showTouches) { onIntent(MirrorIntent.ToggleShowTouches) }
        }
    }
}

private val RecordingRed = Color(0xFFE5484D)

/** 녹화 중이면 빨간 점과 경과 시간. 시작·마무리 중에는 그 상태를 글로 보인다. */
@Composable
private fun RecordingBadge(recording: RecordingState) {
    val text = when (recording) {
        RecordingState.Idle -> return
        RecordingState.Starting -> "녹화 준비 중…"
        RecordingState.Stopping -> "녹화 저장 중…"
        is RecordingState.Recording -> {
            var elapsedMs by remember(recording) { mutableLongStateOf(0L) }
            LaunchedEffect(recording) {
                val start = withFrameMillis { it }
                while (true) {
                    delay(250)
                    elapsedMs = withFrameMillis { it } - start
                }
            }
            "● " + formatElapsed(elapsedMs)
        }
    }
    Text(text, color = RecordingRed, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
}

/** 녹화 중에 메뉴를 열지 않고 바로 멈추는 버튼. 시작·마무리 중에는 누를 수 없다. */
@Composable
private fun RecordingStopButton(recording: RecordingState, onIntent: (MirrorIntent) -> Unit) {
    if (recording is RecordingState.Idle) return
    OutlinedButton(
        onClick = { onIntent(MirrorIntent.StopRecording) },
        enabled = recording is RecordingState.Recording,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RecordingRed),
    ) { Text("■ 정지", style = MaterialTheme.typography.labelLarge) }
}

/** 경과 시간 m:ss (한 시간 넘으면 h:mm:ss). */
fun formatElapsed(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    fun two(v: Long) = v.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
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
