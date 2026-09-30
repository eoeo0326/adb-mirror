package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.isSelectable
import io.github.eoeo0326.adbmirror.feature.mirror.video.VideoSurface

/** ViewModel에 연결된 진입 화면. */
@Composable
fun MirrorRoute(viewModel: MirrorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val session by viewModel.session.collectAsState()
    MirrorScreen(state, session, viewModel::onIntent, modifier)
}

@Composable
fun MirrorScreen(state: MirrorState, session: MirrorSession?, onIntent: (MirrorIntent) -> Unit, modifier: Modifier = Modifier) {
    when (state.screen) {
        Screen.DeviceList -> DeviceListScreen(state, onIntent, modifier)
        Screen.Mirror -> MirroringScreen(state, session, onIntent, modifier)
    }
}

@Composable
private fun DeviceListScreen(state: MirrorState, onIntent: (MirrorIntent) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("기기 선택", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        (state.connection as? Connection.Error)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.devices.isEmpty()) {
            Text("연결된 adb 기기가 없습니다. USB로 연결하고 USB 디버깅을 켜 주세요.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.devices, key = { it.serial }) { device ->
                DeviceRow(device, selected = device.serial == state.selectedSerial) { onIntent(MirrorIntent.SelectDevice(device.serial)) }
            }
        }
        Button(onClick = { onIntent(MirrorIntent.Connect) }, enabled = state.canConnect, modifier = Modifier.fillMaxWidth()) {
            Text("연결")
        }
    }
}

@Composable
private fun DeviceRow(device: Device, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .background(if (selected) colors.primaryContainer else Color.Transparent, shape)
            .clickable(enabled = device.isSelectable, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.model ?: device.serial, fontWeight = FontWeight.Medium)
            if (device.model != null) Text(device.serial, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text(
            when (device.state) {
                DeviceState.Online -> "연결됨"
                DeviceState.Unauthorized -> "기기에서 USB 디버깅을 허용하세요"
                DeviceState.Offline -> "응답 없음"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (device.isSelectable) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun MirroringScreen(state: MirrorState, session: MirrorSession?, onIntent: (MirrorIntent) -> Unit, modifier: Modifier) {
    val mirroring = state.connection as? Connection.Mirroring
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mirroring?.deviceName ?: "연결 중…", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text("보기 전용", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            Switch(checked = state.settings.viewOnly, onCheckedChange = { onIntent(MirrorIntent.ToggleViewOnly) })
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { onIntent(MirrorIntent.Disconnect) }) { Text("연결 끊기") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (session != null) {
                VideoSurface(
                    session = session,
                    videoSize = mirroring?.videoSize,
                    onTouch = { action, x, y -> onIntent(MirrorIntent.Touch(action, x, y)) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("연결 중…")
            }
        }
    }
}
