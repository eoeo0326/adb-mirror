package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.data.conversion.AnimationConverter
import io.github.eoeo0326.adbmirror.core.data.conversion.FfmpegVideoFrameSource
import io.github.eoeo0326.adbmirror.core.data.conversion.FfmpegWebpFrameEncoder
import io.github.eoeo0326.adbmirror.core.data.screenshot.defaultOutputDir
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/** Desktop: 녹화는 저장 폴더(없으면 [defaultDir])에, 변환 결과는 첫 녹화 파일 옆 `<이름>.gif|webp`(있으면 `_2`…)에 둔다. */
class FolderCaptureOutput(private val defaultDir: () -> File) : CaptureOutput {
    override fun recordingDir(outputDir: String?): File = outputDir?.let(::File) ?: defaultDir()

    override suspend fun recordingSaved(files: List<File>): List<String> = files.map { it.absolutePath }

    override fun conversionTempDir(source: File): File = source.parentFile

    override suspend fun publishAnimation(tmp: File, source: File, ext: String): ConversionProgress.Done {
        val base = source.nameWithoutExtension
        val target = generateSequence(1) { it + 1 }
            .map { n -> File(source.parentFile, if (n == 1) "$base.$ext" else "${base}_$n.$ext") }
            .first { !it.exists() }
        Files.move(tmp.toPath(), target.toPath())
        return ConversionProgress.Done(target.absolutePath)
    }
}

/** Desktop 기본 구성: 홈 기준 기본 폴더와 FFmpeg 디코더·WebP 인코더. */
fun FileRecordingRepository(
    scope: CoroutineScope,
    home: File = File(System.getProperty("user.home")),
    now: () -> LocalDateTime = LocalDateTime::now,
    converter: AnimationConverter = AnimationConverter(FfmpegVideoFrameSource(), FfmpegWebpFrameEncoder.createOrNull()),
): FileRecordingRepository = FileRecordingRepository(scope, FolderCaptureOutput { defaultOutputDir(home) }, converter, now)
