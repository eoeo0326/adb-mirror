package io.github.eoeo0326.adbmirror.feature.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateStatus

/** 설정 화면 업데이트 섹션의 상태 줄과 버튼. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateStatusRow(state: UpdateState, onIntent: (UpdateIntent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val message = when (status) {
            UpdateStatus.Idle -> null
            UpdateStatus.Checking -> "확인 중…"
            UpdateStatus.UpToDate -> "최신 버전입니다."
            is UpdateStatus.Available ->
                if (state.canDownload) "새 버전 ${status.info.version}이 있습니다." else "새 버전 ${status.info.version}이 있습니다.\n개발 실행에서는 내려받을 수 없어 릴리즈 페이지에서 받아 주세요."
            is UpdateStatus.Downloading -> "${status.info.version} 내려받는 중" + (status.progress?.let { " ${(it * 100).toInt()}%" } ?: "…")
            is UpdateStatus.Ready -> "${status.update.info.version} 업데이트가 준비됐습니다.\n앱을 다시 시작하면 설치됩니다."
            is UpdateStatus.ManualInstall -> "${status.update.info.version}을 내려받았습니다.\n아래 명령으로 설치해 주세요."
            is UpdateStatus.Failed -> status.message
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (status is UpdateStatus.Failed) colors.error else colors.onSurface) }
        if (status is UpdateStatus.Downloading) {
            val progress = status.progress
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (status is UpdateStatus.ManualInstall) {
            SelectionContainer {
                Text(
                    status.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(8.dp),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                is UpdateStatus.Ready -> Button(onClick = { onIntent(UpdateIntent.InstallNow) }) { Text("다시 시작해 업데이트") }
                is UpdateStatus.Available -> if (state.canDownload) Button(onClick = { onIntent(UpdateIntent.Download) }) { Text("내려받기") }
                is UpdateStatus.ManualInstall -> OutlinedButton(onClick = { onIntent(UpdateIntent.OpenFolder) }) { Text("폴더 열기") }
                else -> Unit
            }
            val busy = status == UpdateStatus.Checking || status is UpdateStatus.Downloading
            if (status !is UpdateStatus.Ready) {
                OutlinedButton(onClick = { onIntent(UpdateIntent.Check) }, enabled = !busy) { Text("지금 확인") }
            }
            if (status is UpdateStatus.Available || status is UpdateStatus.Downloading || status is UpdateStatus.Ready || status is UpdateStatus.ManualInstall) {
                TextButton(onClick = { onIntent(UpdateIntent.OpenNotes) }) { Text("릴리즈 노트") }
            }
        }
    }
}

/**
 * 업데이트가 준비되면 기기 목록 창 위에 띄우는 알림. 미러링 창이 열려 있으면([mirrorsOpen]) 다시 시작하면 닫힌다고 알린다.
 */
@Composable
fun UpdateBanner(state: UpdateState, mirrorsOpen: Boolean, onIntent: (UpdateIntent) -> Unit, modifier: Modifier = Modifier) {
    if (!state.showBanner) return
    val colors = MaterialTheme.colorScheme
    val manual = state.status as? UpdateStatus.ManualInstall
    val version = when (val s = state.status) {
        is UpdateStatus.Ready -> s.update.info.version
        is UpdateStatus.ManualInstall -> s.update.info.version
        else -> return
    }
    Column(
        modifier.fillMaxWidth().background(colors.primaryContainer).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (manual != null) "$version 업데이트를 내려받았습니다." else "$version 업데이트가 준비됐습니다.",
            color = colors.onPrimaryContainer,
            fontWeight = FontWeight.Medium,
        )
        if (manual == null && mirrorsOpen) {
            Text("다시 시작하면 열려 있는 미러링 창이 닫힙니다.", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
        }
        FlowRowButtons(manual != null, onIntent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(manual: Boolean, onIntent: (UpdateIntent) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (manual) {
            Button(onClick = { onIntent(UpdateIntent.OpenFolder) }) { Text("폴더 열기") }
        } else {
            Button(onClick = { onIntent(UpdateIntent.InstallNow) }) { Text("다시 시작해 업데이트") }
        }
        TextButton(onClick = { onIntent(UpdateIntent.OpenNotes) }) { Text("릴리즈 노트") }
        TextButton(onClick = { onIntent(UpdateIntent.Later) }) { Text("나중에") }
    }
}
