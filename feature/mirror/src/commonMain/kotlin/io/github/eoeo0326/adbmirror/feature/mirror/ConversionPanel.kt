package io.github.eoeo0326.adbmirror.feature.mirror

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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import kotlin.math.roundToLong

/** 녹화 파일 → GIF·WebP 변환 화면. 미러링 창 위를 덮는다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversionPanel(draft: ConversionDraft, conversion: ConversionState, onIntent: (MirrorIntent) -> Unit, modifier: Modifier = Modifier) {
    val o = draft.options
    val busy = conversion is ConversionState.Converting
    fun change(transform: (ConversionOptions) -> ConversionOptions) = onIntent(MirrorIntent.ChangeConversionOptions(transform(o)))

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("GIF·WebP로 변환", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                draft.file.substringAfterLast('/').substringAfterLast('\\') + " · ${draft.info.width}×${draft.info.height} · ${seconds(draft.info.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Label("형식")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedFormat.entries.forEach { f ->
                    FilterChip(
                        selected = o.format == f,
                        enabled = !busy && f in draft.formats,
                        onClick = { change { it.copy(format = f) } },
                        label = { Text(if (f == AnimatedFormat.Gif) "GIF" else "WebP") },
                    )
                }
            }
            if (AnimatedFormat.WebP !in draft.formats) Note("이 빌드에는 WebP 인코더가 없어 GIF만 만들 수 있습니다.")

            val end = o.endMs ?: draft.info.durationMs
            Label("구간 ${seconds(o.startMs)} ~ ${seconds(end)} (${seconds(end - o.startMs)})")
            RangeSlider(
                value = o.startMs.toFloat()..end.toFloat(),
                onValueChange = { r ->
                    val s = (r.start / 100).roundToLong() * 100
                    val e = (r.endInclusive / 100).roundToLong() * 100
                    if (e > s) change { it.copy(startMs = s, endMs = e.takeIf { v -> v < draft.info.durationMs }) }
                },
                valueRange = 0f..draft.info.durationMs.toFloat(),
                enabled = !busy,
            )

            Label("fps")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 20, 30).forEach { v ->
                    FilterChip(selected = o.fps == v, enabled = !busy, onClick = { change { it.copy(fps = v) } }, label = { Text("$v") })
                }
            }

            Label("너비")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (WIDTHS.filter { it < draft.info.width } + draft.info.width.coerceIn(ConversionOptions.WIDTH_RANGE)).distinct().forEach { v ->
                    FilterChip(
                        selected = o.outputSize(draft.info).first == v,
                        enabled = !busy,
                        onClick = { change { it.copy(width = v) } },
                        label = { Text(if (v == draft.info.width) "원본 $v" else "$v") },
                    )
                }
            }

            Label("반복")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "무한", 1 to "한 번", 3 to "3번").forEach { (v, label) ->
                    FilterChip(selected = o.loopCount == v, enabled = !busy, onClick = { change { it.copy(loopCount = v) } }, label = { Text(label) })
                }
            }

            if (o.format == AnimatedFormat.WebP) {
                Label("품질 ${o.quality}")
                Slider(
                    value = o.quality.toFloat(),
                    onValueChange = { v -> change { it.copy(quality = v.toInt()) } },
                    valueRange = 0f..100f,
                    enabled = !busy,
                )
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("디더링(색 번짐 줄임, 파일 커짐)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = o.dither, enabled = !busy, onCheckedChange = { v -> change { it.copy(dither = v) } })
                }
            }

            Text(
                "예상 크기 약 ${megabytes(draft.estimatedBytes)}" + if (draft.isLarge) " — 메신저·이슈 첨부 한도를 넘을 수 있습니다. fps·너비·구간을 줄여 보세요." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (draft.isLarge) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            draft.problems.forEach { Note(it) }

            when (conversion) {
                is ConversionState.Converting -> {
                    LinearProgressIndicator(progress = { conversion.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("변환 중 ${(conversion.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                is ConversionState.Done -> Text("저장했습니다: ${conversion.file}", style = MaterialTheme.typography.bodyMedium)
                is ConversionState.Failed -> Text("변환하지 못했습니다: ${conversion.message}", color = Color(0xFFE5484D), style = MaterialTheme.typography.bodyMedium)
                ConversionState.Idle -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) {
                    OutlinedButton(onClick = { onIntent(MirrorIntent.CancelConversion) }) { Text("취소") }
                } else {
                    Button(onClick = { onIntent(MirrorIntent.Convert) }, enabled = draft.problems.isEmpty()) {
                        Text(if (conversion is ConversionState.Done) "다시 변환" else "변환")
                    }
                    OutlinedButton(onClick = { onIntent(MirrorIntent.CloseConversion) }) { Text("닫기") }
                }
            }
        }
    }
}

private val WIDTHS = listOf(240, 320, 480, 720, 1080)

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** 0.1초 단위 "4.4초" */
internal fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}초"

/** "0.4MB" · "12MB" */
internal fun megabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 10) "${(mb * 10).roundToLong() / 10.0}MB" else "${mb.roundToLong()}MB"
}
