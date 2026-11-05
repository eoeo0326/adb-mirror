package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.LibraryCompatibilityInfo
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeInspectorResolverTest {
    private val cache = Files.createTempDirectory("compose-cache").toFile()

    private fun aar(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun artifactUrlFollowsStudioRule() {
        assertEquals("ui", ComposeInspectorResolver.artifactId("1.4.3"))
        assertEquals("ui", ComposeInspectorResolver.artifactId("1.5.0-alpha04"))
        assertEquals("ui-android", ComposeInspectorResolver.artifactId("1.5.0-beta01"))
        assertEquals("ui-android", ComposeInspectorResolver.artifactId("1.7.8"))
        assertEquals(
            "https://dl.google.com/android/maven2/androidx/compose/ui/ui-android/1.7.8/ui-android-1.7.8.aar",
            ComposeInspectorResolver.artifactUrl("1.7.8"),
        )
        assertEquals("https://dl.google.com/android/maven2/androidx/compose/ui/ui/1.4.3/ui-1.4.3.aar", ComposeInspectorResolver.artifactUrl("1.4.3"))
    }

    @Test
    fun comparesPrereleaseQualifiers() {
        val c = ComposeInspectorResolver::compareVersions
        assertTrue(c("1.5.0", "1.5.0-rc01") > 0)
        assertTrue(c("1.5.0-rc01", "1.5.0-beta02") > 0)
        assertTrue(c("1.5.0-beta02", "1.5.0-beta01") > 0)
        assertTrue(c("1.5.0-beta01", "1.5.0-alpha09") > 0)
        assertTrue(c("1.10.0", "1.9.9") > 0)
        assertEquals(0, c("1.7", "1.7.0"))
    }

    @Test
    fun interpretsCompatibilityStatus() {
        val resolver = ComposeInspectorResolver(cache) { error("unused") }
        fun info(status: LibraryCompatibilityInfo.Status, version: String = "") =
            LibraryCompatibilityInfo.newBuilder().setStatus(status).setVersion(version).build()
        assertEquals(ComposeInspectorResolver.Detected.Version("1.7.8"), resolver.interpret(info(LibraryCompatibilityInfo.Status.COMPATIBLE, "1.7.8")))
        assertEquals(ComposeInspectorResolver.Detected.NoCompose, resolver.interpret(info(LibraryCompatibilityInfo.Status.LIBRARY_MISSING)))
        assertTrue(resolver.interpret(info(LibraryCompatibilityInfo.Status.APP_PROGUARDED)) is ComposeInspectorResolver.Detected.Unavailable)
        assertTrue(resolver.interpret(info(LibraryCompatibilityInfo.Status.VERSION_MISSING)) is ComposeInspectorResolver.Detected.Unavailable)
        assertTrue(resolver.interpret(info(LibraryCompatibilityInfo.Status.COMPATIBLE, "")) is ComposeInspectorResolver.Detected.Unavailable)
        assertTrue(resolver.interpret(null) is ComposeInspectorResolver.Detected.Unavailable)
    }

    @Test
    fun downloadsExtractsAndCaches() = runBlocking {
        val urls = mutableListOf<String>()
        val resolver = ComposeInspectorResolver(cache) { url ->
            urls += url
            ByteArrayInputStream(aar("classes.jar" to byteArrayOf(0), "inspector.jar" to "dex".toByteArray()))
        }
        var downloading = 0
        val jar = resolver.inspectorJar("1.7.8") { downloading++ }
        assertEquals("dex", jar.readText())
        assertEquals("compose/1.7.8/compose-ui-inspection.jar", jar.relativeTo(cache).invariantSeparatorsPath)
        assertEquals(jar, resolver.inspectorJar("1.7.8") { downloading++ })
        assertEquals(1, urls.size, "두 번째는 캐시를 쓴다")
        assertEquals(1, downloading)
    }

    @Test
    fun failsWhenAarHasNoInspectorOrDownloadFails() = runBlocking {
        val missing = ComposeInspectorResolver(cache) { ByteArrayInputStream(aar("classes.jar" to byteArrayOf(0))) }
        assertFailsWith<IOException> { missing.inspectorJar("1.6.0") }
        val broken = ComposeInspectorResolver(cache) { throw IOException("HTTP 404") }
        assertFailsWith<IOException> { broken.inspectorJar("1.6.1") }
        assertFalse(cache.resolve("compose/1.6.0/compose-ui-inspection.jar").exists(), "실패하면 캐시에 남기지 않는다")
        assertEquals(emptyList(), cache.resolve("compose/1.6.0").listFiles().orEmpty().toList(), "임시 파일도 지운다")
    }
}
