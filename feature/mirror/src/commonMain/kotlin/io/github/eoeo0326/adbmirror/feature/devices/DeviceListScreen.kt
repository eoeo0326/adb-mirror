package io.github.eoeo0326.adbmirror.feature.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import io.github.eoeo0326.adbmirror.core.domain.model.isSelectable

@Composable
fun DeviceListRoute(viewModel: DeviceListViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    DeviceListScreen(state, viewModel::onIntent, modifier)
}

@Composable
fun DeviceListScreen(state: DeviceListState, onIntent: (DeviceListIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("기기 선택", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (state.devices.isEmpty()) {
            Text(
                "연결된 adb 기기가 없습니다.\n기기를 USB로 연결하고, 개발자 옵션에서 USB 디버깅을 켜 주세요.",
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
private fun DeviceRow(device: Device, selected: Boolean, open: Boolean, onClick: () -> Unit) {
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
    }
}
