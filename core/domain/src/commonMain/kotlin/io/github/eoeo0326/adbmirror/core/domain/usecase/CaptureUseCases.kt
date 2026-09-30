package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.Recording
import io.github.eoeo0326.adbmirror.core.domain.model.Screenshot
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import io.github.eoeo0326.adbmirror.core.domain.repository.RecordingRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.ScreenshotRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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

/**
 * 녹화는 key frame부터 시작해야 한다. 패킷을 받기 시작한 뒤에 key frame을 요청해야
 * 요청에 응답한 config·key frame을 놓치지 않는다.
 */
class StartRecordingUseCase(
    private val recordings: RecordingRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(session: MirrorSession) {
        recordings.start(session, settings.settings.first().outputDir)
        try {
            session.requestKeyFrame()
        } catch (e: Throwable) {
            // 요청이 실패(또는 취소)하면 이미 시작한 녹화를 되돌려, 같은 기기의 다음 녹화를 막지 않게 한다.
            withContext(NonCancellable) { recordings.stop(session.serial) }
            throw e
        }
    }
}

class StopRecordingUseCase(private val recordings: RecordingRepository) {
    suspend operator fun invoke(session: MirrorSession): Recording? = recordings.stop(session.serial)
}

class GetVideoInfoUseCase(private val recordings: RecordingRepository) {
    suspend operator fun invoke(file: String): VideoInfo = recordings.info(file)
}

/** 옵션이 허용 범위를 벗어나면 변환을 시작하지 않고 [IllegalArgumentException]을 던진다. */
class ConvertRecordingUseCase(private val recordings: RecordingRepository) {
    operator fun invoke(file: String, options: ConversionOptions): Flow<ConversionProgress> {
        val problems = options.problems()
        require(problems.isEmpty()) { problems.joinToString() }
        return recordings.convert(file, options)
    }
}
