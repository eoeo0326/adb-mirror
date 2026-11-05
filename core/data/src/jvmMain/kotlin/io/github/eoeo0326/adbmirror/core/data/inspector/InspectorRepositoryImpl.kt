package io.github.eoeo0326.adbmirror.core.data.inspector

import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorConnection
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorException
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.github.eoeo0326.adbmirror.core.domain.repository.InspectorRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import io.github.eoeo0326.adbmirror.core.inspector.AdbDeviceShell
import io.github.eoeo0326.adbmirror.core.inspector.ComposeInspectorResolver
import io.github.eoeo0326.adbmirror.core.inspector.DeviceShell
import io.github.eoeo0326.adbmirror.core.inspector.InspectorConnectionImpl
import io.github.eoeo0326.adbmirror.core.inspector.StudioInstall
import io.github.eoeo0326.adbmirror.core.inspector.StudioLocator
import io.github.eoeo0326.adbmirror.core.inspector.TransportLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop Layout Inspector. 설정의 Android Studio 위치(없으면 기본 위치의 최신 설치)에서 기기 쪽 파일을 찾아
 * transport를 띄우고 [InspectorConnectionImpl]을 돌려준다. Compose inspector는 [cacheDir] 아래에 캐시한다.
 */
class InspectorRepositoryImpl(
    private val shell: DeviceShell,
    private val settings: SettingsRepository,
    private val cacheDir: File,
    private val locate: (configuredPath: String?) -> StudioInstall? = { StudioLocator.locate(it) },
    private val launcher: TransportLauncher = TransportLauncher(shell, log = { println("inspector: $it") }),
) : InspectorRepository {
    constructor(adb: AdbBinaryTransport, settings: SettingsRepository, cacheDir: File) : this(AdbDeviceShell(adb), settings, cacheDir)

    override suspend fun connect(serial: String, onStep: (InspectorStep) -> Unit): InspectorConnection {
        onStep(InspectorStep.LocatingStudio)
        val configured = settings.settings.first().androidStudioPath
        val install = withContext(Dispatchers.IO) { locate(configured) }
            ?: throw InspectorException(InspectorFailure.StudioNotFound, "Android Studio를 찾지 못했습니다" + (configured?.let { " ($it)" } ?: ""))
        val transport = launcher.launch(serial, install, onStep)
        return InspectorConnectionImpl(install, transport, shell, ComposeInspectorResolver(File(cacheDir, "inspector")), log = { println("inspector: $it") })
    }
}
