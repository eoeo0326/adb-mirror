package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.Recording
import io.github.eoeo0326.adbmirror.core.domain.model.Screenshot
import io.github.eoeo0326.adbmirror.core.domain.repository.RecordingRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.ScreenshotRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class CaptureScreenshotUseCase(private val screenshots: ScreenshotRepository) {
    suspend operator fun invoke(serial: String): Screenshot = screenshots.capture(serial)
}

class CopyScreenshotUseCase(private val screenshots: ScreenshotRepository) {
    suspend operator fun invoke(screenshot: Screenshot) = screenshots.copyToClipboard(screenshot)
}

/** 설정의 저장 폴더에 저장하고 파일 경로를 돌려준다. */
class SaveScreenshotUseCase(
    private val screenshots: ScreenshotRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(screenshot: Screenshot): String =
        screenshots.save(screenshot, settings.settings.first().outputDir)
}

/** 녹화는 key frame부터 시작해야 해서, 시작 직전에 key frame을 요청한다. */
class StartRecordingUseCase(
    private val recordings: RecordingRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(session: MirrorSession) {
        session.requestKeyFrame()
        recordings.start(session, settings.settings.first().outputDir)
    }
}

class StopRecordingUseCase(private val recordings: RecordingRepository) {
    suspend operator fun invoke(): Recording? = recordings.stop()
}

/** 옵션이 허용 범위를 벗어나면 변환을 시작하지 않고 [IllegalArgumentException]을 던진다. */
class ConvertRecordingUseCase(private val recordings: RecordingRepository) {
    operator fun invoke(file: String, options: ConversionOptions): Flow<ConversionProgress> {
        val problems = options.problems()
        require(problems.isEmpty()) { problems.joinToString() }
        return recordings.convert(file, options)
    }
}
