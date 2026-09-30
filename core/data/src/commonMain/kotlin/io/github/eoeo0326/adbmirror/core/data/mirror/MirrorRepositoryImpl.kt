package io.github.eoeo0326.adbmirror.core.data.mirror

import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.repository.MirrorRepository
import kotlinx.coroutines.CoroutineScope

class MirrorRepositoryImpl(
    private val launcher: ScrcpyServerLauncher,
    private val scope: CoroutineScope,
) : MirrorRepository {
    override suspend fun start(serial: String, options: MirrorOptions): MirrorSession =
        ScrcpyMirrorSession(serial, launcher.launch(serial, options), scope)
}
