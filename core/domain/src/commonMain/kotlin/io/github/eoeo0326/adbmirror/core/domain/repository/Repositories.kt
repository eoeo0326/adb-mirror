package io.github.eoeo0326.adbmirror.core.domain.repository

import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
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

/** 기기(serial)마다 녹화 하나. */
interface RecordingRepository {
    /** 패킷 구독이 시작된 뒤에 반환한다. 그 뒤에 요청한 key frame은 놓치지 않는다. 이미 녹화 중이면 오류. */
    suspend fun start(session: MirrorSession, outputDir: String?)

    /** 남은 조각까지 쓰고 파일을 닫는다. 그 기기가 녹화 중이 아니면 null. */
    suspend fun stop(serial: String): Recording?
    /** 변환 전에 길이·크기를 읽는다. */
    suspend fun info(file: String): VideoInfo

    /** 이 플랫폼에서 만들 수 있는 형식(예: WebP 인코더가 없으면 GIF만). */
    fun supportedFormats(): Set<AnimatedFormat>

    /**
     * [files]를 차례로 이어(회전으로 나뉜 part) 하나의 애니메이션으로 만든다. 캔버스는 첫 part 크기이고,
     * 결과는 첫 파일 옆에 같은 이름의 .gif/.webp로 쓴다. 흐름을 취소하면 쓰던 파일을 지운다.
     */
    fun convert(files: List<String>, options: ConversionOptions): Flow<ConversionProgress>
}

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
