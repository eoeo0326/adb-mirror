package io.github.eoeo0326.adbmirror.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
import io.github.eoeo0326.adbmirror.core.domain.usecase.WirelessInput

/** 무선 기기 추가 화면. 기기 목록과 같은 ViewModel을 쓴다. [onBack]이 있으면 뒤로 버튼이 있는 TopBar를 둔다(Android). */
@Composable
fun WirelessAddRoute(viewModel: DeviceListViewModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val form = state.wireless ?: return
    WirelessAddScreen(form, viewModel::onIntent, onBack, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessAddScreen(form: WirelessForm, onIntent: (DeviceListIntent) -> Unit, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        // Desktop은 창 제목이 "무선 기기 추가"라 TopBar 없이 탭부터 보인다.
        if (onBack != null) {
            TopAppBar(
                title = { Text("무선 기기 추가", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "뒤로" }) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        }
        PrimaryTabRow(selectedTabIndex = form.tab.ordinal) {
            WirelessTab.entries.forEach { tab ->
                Tab(
                    selected = form.tab == tab,
                    onClick = { onIntent(DeviceListIntent.SelectWirelessTab(tab)) },
                    text = { Text(tab.label) },
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (form.tab) {
                WirelessTab.Pair -> PairTab(form, onIntent)
                WirelessTab.Connect -> ConnectTab(form, onIntent)
            }
            form.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (form.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val WirelessTab.label: String
    get() = when (this) {
        WirelessTab.Pair -> "페어링"
        WirelessTab.Connect -> "연결"
    }

@Composable
private fun PairTab(form: WirelessForm, onIntent: (DeviceListIntent) -> Unit) {
    fun edit(f: WirelessForm) = onIntent(DeviceListIntent.EditWireless(f))
    Help(
        "처음 한 번은 페어링이 필요합니다.",
        "기기에서 설정 > 개발자 옵션 > 무선 디버깅 > \"페어링 코드로 기기 페어링\"을 열고, 그 창의 IP 주소·포트·코드를 입력하세요.",
        if (form.canPairByNotification) {
            "이 폰 자신은 \"알림으로 이 폰 페어링\"을 누른 뒤, 설정의 페어링 창에 나온 코드를 알림에 답장으로 입력하세요."
        } else {
            "기기와 이 컴퓨터가 같은 Wi-Fi에 있어야 합니다."
        },
    )
    if (form.canPairByNotification) {
        OutlinedButton(onClick = { onIntent(DeviceListIntent.PairByNotification) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
            Text("알림으로 이 폰 페어링")
        }
    }
    FoundServices(form, WirelessService.Kind.Pairing, onIntent)
    HostField(form) { edit(form.copy(host = it)) }
    Field("페어링 포트", form.pairPort, KeyboardType.Number, !form.busy) { edit(form.copy(pairPort = it)) }
    Field("코드 6자리", form.code, KeyboardType.Number, !form.busy) { edit(form.copy(code = it)) }
    Button(onClick = { onIntent(DeviceListIntent.Pair) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) { Text("페어링") }
}

@Composable
private fun ConnectTab(form: WirelessForm, onIntent: (DeviceListIntent) -> Unit) {
    fun edit(f: WirelessForm) = onIntent(DeviceListIntent.EditWireless(f))
    Help(
        "페어링한 기기는 무선 디버깅 화면 위쪽의 \"IP 주소 및 포트\"로 연결합니다.",
        "무선 디버깅을 다시 켜면 포트가 바뀝니다.",
    )
    FoundServices(form, WirelessService.Kind.Connect, onIntent)
    HostField(form) { edit(form.copy(host = it)) }
    Field("연결 포트", form.connectPort, KeyboardType.Number, !form.busy) { edit(form.copy(connectPort = it)) }
    Button(onClick = { onIntent(DeviceListIntent.ConnectWireless) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) { Text("연결") }
}

/** 안내 문구. 문장마다 줄을 바꾼다. */
@Composable
private fun Help(vararg sentences: String) {
    Text(sentences.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** IP 주소 칸. 끝자리만 입력하면 [WirelessInput.DEFAULT_SUBNET] 대역으로 본다(바꾸는 일은 UseCase가 한다). */
@Composable
private fun HostField(form: WirelessForm, onChange: (String) -> Unit) {
    Field(
        label = "IP 주소",
        value = form.host,
        type = KeyboardType.Uri,
        enabled = !form.busy,
        placeholder = "${WirelessInput.DEFAULT_SUBNET}XX",
        supporting = "끝자리만 입력하면 ${WirelessInput.DEFAULT_SUBNET}{숫자}로 연결합니다.\n다른 대역이면 IP 주소 전체를 입력하세요.",
        onChange = onChange,
    )
}

/** 같은 네트워크에서 찾은 [kind] 서비스. 연결 서비스는 누르면 바로 연결하고, 페어링 서비스는 주소·포트를 채운다. */
@Composable
private fun FoundServices(form: WirelessForm, kind: WirelessService.Kind, onIntent: (DeviceListIntent) -> Unit) {
    val services = form.found.filter { it.kind == kind }
    if (services.isEmpty()) return
    Text("이 네트워크에서 찾음", style = MaterialTheme.typography.labelMedium)
    services.forEach { service ->
        OutlinedButton(onClick = { onIntent(DeviceListIntent.UseService(service)) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
            Text("${service.host}:${service.port}")
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    type: KeyboardType,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = modifier.fillMaxWidth(),
    )
}
