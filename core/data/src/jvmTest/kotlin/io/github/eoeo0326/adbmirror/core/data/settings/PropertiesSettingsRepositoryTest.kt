package io.github.eoeo0326.adbmirror.core.data.settings

import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropertiesSettingsRepositoryTest {
    private val dir = Files.createTempDirectory("adbmirror-settings").toFile()
    private val file = File(dir, "nested/settings.properties")

    @Test
    fun missingFileGivesDefaults() {
        assertEquals(Settings(), PropertiesSettingsRepository(file).settings.value)
        assertFalse(file.exists())
    }

    @Test
    fun updatePersistsAndReloads() = runTest {
        val repo = PropertiesSettingsRepository(file)
        repo.update { it.copy(maxSize = 1920, maxFps = 30, viewOnly = true, touchEffect = false, showTouches = true, outputDir = "/사진/샷 폴더", adbPath = "C:\\sdk\\adb.exe") }
        assertTrue(file.isFile)
        val reloaded = PropertiesSettingsRepository(file).settings.value
        assertEquals(Settings(1920, 30, viewOnly = true, touchEffect = false, showTouches = true, outputDir = "/사진/샷 폴더", adbPath = "C:\\sdk\\adb.exe"), reloaded)
        assertEquals(listOf("settings.properties"), file.parentFile.list()!!.toList(), "임시 파일이 남지 않는다")
    }

    @Test
    fun clearingOptionalValuesRemovesThem() = runTest {
        val repo = PropertiesSettingsRepository(file)
        repo.update { it.copy(outputDir = "/a") }
        repo.update { it.copy(outputDir = null) }
        assertEquals(null, PropertiesSettingsRepository(file).settings.value.outputDir)
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        file.parentFile.mkdirs()
        file.writeText("maxSize=12\nmaxFps=abc\nviewOnly=yes\ntouchEffect=false\noutputDir=\n")
        assertEquals(Settings(touchEffect = false), PropertiesSettingsRepository(file).settings.value)
    }

    @Test
    fun updateSanitizes() = runTest {
        val repo = PropertiesSettingsRepository(file)
        repo.update { it.copy(maxFps = 999, adbPath = "  ") }
        assertEquals(Settings(), repo.settings.value)
    }
}
