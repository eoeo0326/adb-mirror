package io.github.eoeo0326.adbmirror.core.domain.repository

import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.Recording
import io.github.eoeo0326.adbmirror.core.domain.model.Screenshot
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    /** 연결·해제를 반영하는 기기 목록. */
    fun devices(): Flow<List<Device>>

    /** 기기의 show_touches를 바꾸고 원래 값을 돌려준다. 복원할 때 그 값을 다시 넘긴다. */
    suspend fun setShowTouches(serial: String, enabled: Boolean): Boolean
}

interface MirrorRepository {
    suspend fun start(serial: String, options: MirrorOptions): MirrorSession
}

interface ScreenshotRepository {
    suspend fun capture(serial: String): Screenshot
    suspend fun copyToClipboard(screenshot: Screenshot)

    /** 저장한 파일 경로를 돌려준다. */
    suspend fun save(screenshot: Screenshot, outputDir: String?): String
}

interface RecordingRepository {
    suspend fun start(session: MirrorSession, outputDir: String?)

    /** 녹화 중이 아니면 null. */
    suspend fun stop(): Recording?
    fun convert(file: String, options: ConversionOptions): Flow<ConversionProgress>
}

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
