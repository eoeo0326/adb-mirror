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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.feature.mirror.video.VideoSurface

/** ViewModel에 연결된 미러링 창 내용. */
@Composable
fun MirrorRoute(viewModel: MirrorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val session by viewModel.session.collectAsState()
    MirrorScreen(state, session, viewModel::onIntent, modifier)
}

@Composable
fun MirrorScreen(state: MirrorState, session: MirrorSession?, onIntent: (MirrorIntent) -> Unit, modifier: Modifier = Modifier) {
    val mirroring = state.connection as? Connection.Mirroring
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mirroring?.deviceName ?: state.device.model ?: state.device.serial, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text("보기 전용", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            Switch(checked = state.settings.viewOnly, onCheckedChange = { onIntent(MirrorIntent.ToggleViewOnly) })
            if (session != null) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { onIntent(MirrorIntent.Disconnect) }) { Text("연결 끊기") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val c = state.connection) {
                is Connection.Mirroring -> if (session != null) {
                    VideoSurface(
                        session = session,
                        videoSize = c.videoSize,
                        onTouch = { action, x, y -> onIntent(MirrorIntent.Touch(action, x, y)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Connection.Connecting -> Text("연결 중…")
                is Connection.Error -> Disconnected("연결이 끊겼습니다: ${c.message}", onIntent)
                Connection.Idle -> Disconnected("연결을 끊었습니다", onIntent)
            }
        }
    }
}

@Composable
private fun Disconnected(message: String, onIntent: (MirrorIntent) -> Unit) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, textAlign = TextAlign.Center)
        Button(onClick = { onIntent(MirrorIntent.Connect) }) { Text("다시 연결") }
    }
}
