package io.github.eoeo0326.adbmirror.core.domain

import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.Recording
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.repository.DeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.MirrorRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.RecordingRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)
    override suspend fun update(transform: (Settings) -> Settings) = settings.update(transform)
}

class FakeDeviceRepository(private val list: List<Device>) : DeviceRepository {
    val showTouchesCalls = mutableListOf<Pair<String, Boolean>>()
    var currentShowTouches = false

    override fun devices(): Flow<List<Device>> = flowOf(list)
    override suspend fun setShowTouches(serial: String, enabled: Boolean): Boolean {
        showTouchesCalls += serial to enabled
        return currentShowTouches.also { currentShowTouches = enabled }
    }
}

class FakeSession(override val serial: String = "S1") : MirrorSession {
    val log = mutableListOf<String>()
    val touches = mutableListOf<TouchEvent>()
    override val events: Flow<SessionEvent> = emptyFlow()
    override val packets: Flow<EncodedPacket> = emptyFlow()
    override suspend fun sendTouch(event: TouchEvent) { touches += event }
    var failKeyFrame: String? = null
    override suspend fun requestKeyFrame() {
        log += "keyFrame"
        failKeyFrame?.let { error(it) }
    }
    override suspend fun stop() { log += "stop" }
}

class FakeMirrorRepository(private val session: FakeSession = FakeSession()) : MirrorRepository {
    var lastStart: Pair<String, MirrorOptions>? = null
    override suspend fun start(serial: String, options: MirrorOptions): MirrorSession {
        lastStart = serial to options
        return session
    }
}

class FakeRecordingRepository(private val session: FakeSession) : RecordingRepository {
    var startedDir: String? = "unset"
    override suspend fun start(session: MirrorSession, outputDir: String?) {
        this.session.log += "recordStart"
        startedDir = outputDir
    }
    override suspend fun stop(serial: String): Recording? {
        session.log += "recordStop:$serial"
        return null
    }
    override suspend fun info(file: String) = VideoInfo(10_000, 606, 1280)
    override fun supportedFormats() = AnimatedFormat.entries.toSet()
    override fun convert(file: String, options: ConversionOptions): Flow<ConversionProgress> =
        flowOf(ConversionProgress.Done("$file.${options.format.name.lowercase()}"))
}
