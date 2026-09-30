package io.github.eoeo0326.adbmirror.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.Settings

/** 플랫폼이 채워 주는 부분. 고르기 창이 없는 플랫폼은 null로 두면 버튼을 숨긴다. */
class SettingsPlatform(
    /** 설정 파일 위치 안내(예: "…/settings.properties (포터블)") */
    val storageDescription: String,
    val defaultOutputDescription: String,
    val pickFolder: (() -> String?)? = null,
    val pickAdb: (() -> String?)? = null,
)

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, platform: SettingsPlatform, modifier: Modifier = Modifier) {
    val settings by viewModel.state.collectAsState()
    SettingsScreen(settings, platform, viewModel::onIntent, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: Settings, platform: SettingsPlatform, onIntent: (SettingsIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Section("미러링", "다음 연결부터 적용됩니다.")
        Text("해상도(긴 변)", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.MAX_SIZE_CHOICES.forEach { v ->
                FilterChip(selected = settings.maxSize == v, onClick = { onIntent(SettingsIntent.SetMaxSize(v)) }, label = { Text(if (v == 0) "원본" else "$v") })
            }
        }
        Text("최대 fps", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.FPS_CHOICES.forEach { v ->
                FilterChip(selected = settings.maxFps == v, onClick = { onIntent(SettingsIntent.SetMaxFps(v)) }, label = { Text("$v") })
            }
        }

        Section("입력·표시")
        Toggle("보기 전용(터치 보내지 않음)", settings.viewOnly) { onIntent(SettingsIntent.ToggleViewOnly) }
        Toggle("클릭 이펙트", settings.touchEffect) { onIntent(SettingsIntent.ToggleTouchEffect) }
        Toggle("기기에 터치 표시", settings.showTouches) { onIntent(SettingsIntent.ToggleShowTouches) }

        Section("저장 폴더", "스크린샷·녹화를 저장합니다.")
        PathRow(
            value = settings.outputDir ?: platform.defaultOutputDescription,
            pick = platform.pickFolder?.let { pick -> { pick()?.let { onIntent(SettingsIntent.SetOutputDir(it)) } } },
            reset = if (settings.outputDir != null) ({ onIntent(SettingsIntent.SetOutputDir(null)) }) else null,
            resetLabel = "기본 위치",
        )

        Section("adb", "바꾸면 앱을 다시 시작해야 적용됩니다.")
        PathRow(
            value = settings.adbPath ?: "자동으로 찾기(PATH · ANDROID_HOME · 기본 SDK 위치)",
            pick = platform.pickAdb?.let { pick -> { pick()?.let { onIntent(SettingsIntent.SetAdbPath(it)) } } },
            reset = if (settings.adbPath != null) ({ onIntent(SettingsIntent.SetAdbPath(null)) }) else null,
            resetLabel = "자동",
        )

        HorizontalDivider()
        Text(platform.storageDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, note: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun PathRow(value: String, pick: (() -> Unit)?, reset: (() -> Unit)?, resetLabel: String) {
    Text(value, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pick?.let { OutlinedButton(onClick = it) { Text("찾아보기…") } }
        reset?.let { TextButton(onClick = it) { Text(resetLabel) } }
    }
}
