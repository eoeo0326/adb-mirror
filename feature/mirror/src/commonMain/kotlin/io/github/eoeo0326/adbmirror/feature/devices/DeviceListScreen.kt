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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
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
                if (state.wireless != null) "연결된 기기가 없습니다.\n아래에서 무선 디버깅 기기를 페어링·연결하세요."
                else "연결된 adb 기기가 없습니다.\n기기를 USB로 연결하고, 개발자 옵션에서 USB 디버깅을 켜 주세요.",
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
                    onDisconnect = if (state.wireless != null) ({ onIntent(DeviceListIntent.Disconnect(device.serial)) }) else null,
                )
            }
            state.wireless?.let { form -> item(key = "wireless") { WirelessCard(form, onIntent) } }
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

@Composable
private fun WirelessCard(form: WirelessForm, onIntent: (DeviceListIntent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    fun edit(f: WirelessForm) = onIntent(DeviceListIntent.EditWireless(f))
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("무선 기기 추가", fontWeight = FontWeight.Medium)
        Text(
            "기기에서 설정 > 개발자 옵션 > 무선 디버깅을 켜세요. 처음 한 번은 \"페어링 코드로 기기 페어링\"의 값으로 페어링하고, 그다음부터는 무선 디버깅 화면의 IP 주소·포트로 연결합니다. " +
                if (form.canPairByNotification) {
                    "이 폰 자신은 \"알림으로 이 폰 페어링\"을 누른 뒤, 설정의 페어링 창에 나온 코드를 알림에 답장으로 입력하세요."
                } else {
                    "이 폰 자신을 페어링할 때는 페어링 창이 닫히지 않도록 이 앱을 팝업 화면이나 화면 분할로 띄우세요."
                },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        if (form.canPairByNotification) {
            OutlinedButton(onClick = { onIntent(DeviceListIntent.PairByNotification) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
                Text("알림으로 이 폰 페어링")
            }
        }
        if (form.found.isNotEmpty()) FoundServices(form, onIntent)
        Field("IP 주소", form.host, KeyboardType.Uri, !form.busy) { edit(form.copy(host = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("페어링 포트", form.pairPort, KeyboardType.Number, !form.busy, Modifier.weight(1f)) { edit(form.copy(pairPort = it)) }
            Field("코드 6자리", form.code, KeyboardType.Number, !form.busy, Modifier.weight(1f)) { edit(form.copy(code = it)) }
            OutlinedButton(onClick = { onIntent(DeviceListIntent.Pair) }, enabled = !form.busy) { Text("페어링") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("연결 포트", form.connectPort, KeyboardType.Number, !form.busy, Modifier.weight(1f)) { edit(form.copy(connectPort = it)) }
            Button(onClick = { onIntent(DeviceListIntent.ConnectWireless) }, enabled = !form.busy) { Text("연결") }
        }
        form.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (form.failed) colors.error else colors.onSurfaceVariant)
        }
    }
}

/** 같은 네트워크에서 찾은 무선 디버깅 서비스. 연결 서비스는 누르면 바로 연결하고, 페어링 서비스는 주소·포트를 채운다. */
@Composable
private fun FoundServices(form: WirelessForm, onIntent: (DeviceListIntent) -> Unit) {
    Text("이 네트워크에서 찾음", style = MaterialTheme.typography.labelMedium)
    form.found.sortedBy { it.kind }.forEach { service ->
        val label = when (service.kind) {
            WirelessService.Kind.Pairing -> "페어링 ${service.host}:${service.port}"
            WirelessService.Kind.Connect -> "연결 ${service.host}:${service.port}"
        }
        OutlinedButton(onClick = { onIntent(DeviceListIntent.UseService(service)) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
            Text(label)
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType, enabled: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = modifier.fillMaxWidth(),
    )
}
