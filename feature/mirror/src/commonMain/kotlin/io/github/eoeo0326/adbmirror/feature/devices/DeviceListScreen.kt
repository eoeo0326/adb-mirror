package io.github.eoeo0326.adbmirror.feature.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.isSelectable
import io.github.eoeo0326.adbmirror.core.domain.model.isWireless

@Composable
fun DeviceListRoute(viewModel: DeviceListViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    DeviceListScreen(state, viewModel::onIntent, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(state: DeviceListState, onIntent: (DeviceListIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("기기 선택", fontWeight = FontWeight.SemiBold) },
            actions = {
                // 무선 디버깅을 지원하는 플랫폼에서만 기기를 추가할 수 있다.
                if (state.wireless != null) {
                    IconButton(onClick = { onIntent(DeviceListIntent.OpenWirelessAdd) }, modifier = Modifier.semantics { contentDescription = "무선 기기 추가" }) {
                        Text("+", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            },
        )
        DeviceListBody(state, onIntent)
    }
}

@Composable
private fun ColumnScope.DeviceListBody(state: DeviceListState, onIntent: (DeviceListIntent) -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.devices.isEmpty()) {
            Text(
                when {
                    state.wireless == null -> "연결된 adb 기기가 없습니다.\n기기를 USB로 연결하고, 개발자 옵션에서 USB 디버깅을 켜 주세요."
                    state.usb -> "연결된 기기가 없습니다.\n기기를 USB로 연결하고 USB 디버깅을 켜거나, 오른쪽 위 + 버튼으로 무선 디버깅 기기를 추가하세요."
                    else -> "연결된 기기가 없습니다.\n오른쪽 위 + 버튼으로 무선 디버깅 기기를 추가하세요."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.devices, key = { it.serial }) { device ->
                DeviceRow(
                    device = device,
                    selected = device.serial == state.selectedSerial,
                    open = device.serial in state.openSerials,
                    onClick = { onIntent(DeviceListIntent.Select(device.serial)) },
                    onDisconnect = if (state.wireless != null && device.isWireless) ({ onIntent(DeviceListIntent.Disconnect(device.serial)) }) else null,
                )
            }
        }
        val opening = state.selected?.serial in state.openSerials
        Button(onClick = { onIntent(DeviceListIntent.Open) }, enabled = state.canOpen, modifier = Modifier.fillMaxWidth()) {
            Text(if (opening) "미러링 창 보기" else "미러링 시작")
        }
    }
}

@Composable
private fun DeviceRow(device: Device, selected: Boolean, open: Boolean, onClick: () -> Unit, onDisconnect: (() -> Unit)?) {
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
            when {
                open -> "미러링 중"
                device.state == DeviceState.Online -> "연결됨"
                device.state == DeviceState.Unauthorized -> "기기에서 USB 디버깅을 허용하세요"
                else -> "응답 없음"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (device.isSelectable) colors.primary else colors.onSurfaceVariant,
        )
        if (onDisconnect != null && !open) TextButton(onClick = onDisconnect) { Text("끊기") }
    }
}
