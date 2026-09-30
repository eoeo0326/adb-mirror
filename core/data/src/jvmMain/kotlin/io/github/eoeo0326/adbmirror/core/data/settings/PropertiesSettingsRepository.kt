package io.github.eoeo0326.adbmirror.core.data.settings

import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * `.properties` 파일에 저장하는 설정. 만들 때 한 번 읽고, 바뀔 때마다 임시 파일에 쓴 뒤 바꿔치기한다
 * (쓰는 도중 앱이 죽어도 이전 파일이 남는다). 읽지 못한 항목은 기본값을 쓴다.
 */
class PropertiesSettingsRepository(private val file: File) : SettingsRepository {
    private val state = MutableStateFlow(load(file))
    override val settings: StateFlow<Settings> = state
    private val writeLock = Mutex()

    override suspend fun update(transform: (Settings) -> Settings) = withContext(NonCancellable + Dispatchers.IO) {
        // 파일 쓰기 순서가 상태 변경 순서와 같도록 락 안에서 둘 다 한다.
        writeLock.withLock {
            val next = transform(state.value).sanitized()
            if (next == state.value) return@withLock
            state.value = next
            try {
                write(file, next)
            } catch (e: Exception) {
                System.err.println("설정을 저장하지 못했습니다(${file.path}): ${e.message}")
            }
        }
    }

    internal companion object {
        private const val SCHEMA = 1

        fun load(file: File): Settings {
            if (!file.isFile) return Settings()
            val p = try {
                Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
            } catch (e: Exception) {
                System.err.println("설정 파일을 읽지 못해 기본값을 씁니다(${file.path}): ${e.message}")
                return Settings()
            }
            val d = Settings()
            return Settings(
                maxSize = p.getProperty("maxSize")?.toIntOrNull() ?: d.maxSize,
                maxFps = p.getProperty("maxFps")?.toIntOrNull() ?: d.maxFps,
                viewOnly = p.getProperty("viewOnly")?.toBooleanStrictOrNull() ?: d.viewOnly,
                touchEffect = p.getProperty("touchEffect")?.toBooleanStrictOrNull() ?: d.touchEffect,
                showTouches = p.getProperty("showTouches")?.toBooleanStrictOrNull() ?: d.showTouches,
                outputDir = p.getProperty("outputDir"),
                adbPath = p.getProperty("adbPath"),
            ).sanitized()
        }

        fun write(file: File, s: Settings) {
            val p = Properties()
            p["schema"] = SCHEMA.toString()
            p["maxSize"] = s.maxSize.toString()
            p["maxFps"] = s.maxFps.toString()
            p["viewOnly"] = s.viewOnly.toString()
            p["touchEffect"] = s.touchEffect.toString()
            p["showTouches"] = s.showTouches.toString()
            s.outputDir?.let { p["outputDir"] = it }
            s.adbPath?.let { p["adbPath"] = it }
            val dir = file.absoluteFile.parentFile.also { it.mkdirs() }
            val tmp = File.createTempFile("settings", ".tmp", dir)
            try {
                tmp.writer(Charsets.UTF_8).use { p.store(it, "ADB Mirror settings") }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tmp.delete()
            }
        }
    }
}
