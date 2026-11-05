package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.ArtifactCoordinate
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.LibraryCompatibility
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.LibraryCompatibilityInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * 앱이 쓰는 Compose UI 버전을 확인하고, 그 버전 AAR 안의 `inspector.jar`를 내려받아 캐시한다.
 * Android Studio와 같이 Google Maven의 `androidx.compose.ui:ui`(1.5.0-beta01부터는 `ui-android`) AAR을 쓴다.
 */
class ComposeInspectorResolver(
    /** `<SettingsLocation.dir>/inspector`. 버전별로 `compose/<v>/compose-ui-inspection.jar`에 둔다. */
    private val cacheDir: File,
    private val open: (url: String) -> InputStream = ::httpGet,
) {
    /** 앱의 Compose 확인 결과. */
    sealed interface Detected {
        data class Version(val version: String) : Detected

        /** Compose를 쓰지 않는 앱. 안내 없이 View 트리만 보인다. */
        data object NoCompose : Detected

        /** Compose는 있지만 정보를 볼 수 없다. */
        data class Unavailable(val reason: String) : Detected
    }

    fun interpret(info: LibraryCompatibilityInfo?): Detected = when {
        info == null -> Detected.Unavailable("Compose 버전을 확인하지 못했습니다")
        info.status == LibraryCompatibilityInfo.Status.LIBRARY_MISSING -> Detected.NoCompose
        info.status == LibraryCompatibilityInfo.Status.APP_PROGUARDED -> Detected.Unavailable("앱이 난독화돼 있습니다")
        info.status == LibraryCompatibilityInfo.Status.VERSION_MISSING -> Detected.Unavailable("Compose 버전 정보가 없습니다")
        info.status == LibraryCompatibilityInfo.Status.SERVICE_ERROR -> Detected.Unavailable(info.errorMessage.ifEmpty { "버전 확인 오류" })
        info.version.isBlank() -> Detected.Unavailable("Compose 버전 정보가 없습니다")
        else -> Detected.Version(info.version)
    }

    /** 캐시에 있으면 그대로, 없으면 내려받아 꺼낸다. 실패하면 [IOException]. */
    suspend fun inspectorJar(version: String, onDownloading: () -> Unit = {}): File = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "compose/$version/$JAR_NAME")
        if (target.isFile && target.length() > 0) return@withContext target
        onDownloading()
        target.parentFile.mkdirs()
        val temp = File.createTempFile("compose-inspector", ".jar", target.parentFile)
        try {
            val found = open(artifactUrl(version)).use { input ->
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }.firstOrNull { it.name == "inspector.jar" }?.let {
                        temp.outputStream().use { out -> zip.copyTo(out) }
                        true
                    } ?: false
                }
            }
            if (!found) throw IOException("AAR에 inspector.jar가 없습니다: $version")
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            target
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val JAR_NAME = "compose-ui-inspection.jar"
        private const val MAVEN = "https://dl.google.com/android/maven2"

        /** 버전을 묻는 대상. 버전을 비워 보내면 앱에 있는 버전이 응답에 담긴다. */
        fun compatibilityTarget(version: String = ""): LibraryCompatibility = LibraryCompatibility.newBuilder()
            .setCoordinate(ArtifactCoordinate.newBuilder().setGroupId("androidx.compose.ui").setArtifactId("ui").setVersion(version))
            .addExpectedLibraryClassNames("androidx.compose.ui.Modifier")
            .build()

        /** Studio와 같이 1.5.0-beta01부터는 `ui-android`, 그 전은 `ui`. */
        fun artifactId(version: String): String = if (compareVersions(version, "1.5.0-beta01") >= 0) "ui-android" else "ui"

        fun artifactUrl(version: String): String {
            val artifact = artifactId(version)
            return "$MAVEN/androidx/compose/ui/$artifact/$version/$artifact-$version.aar"
        }

        /** `1.7.8`, `1.5.0-beta01`, `1.6.0-rc02` 비교. 정식 > rc > beta > alpha > dev. */
        fun compareVersions(a: String, b: String): Int {
            fun parse(v: String): List<Int> {
                val (core, qualifier) = v.split('-', limit = 2).let { it[0] to it.getOrNull(1) }
                val numbers = core.split('.').map { it.toIntOrNull() ?: 0 }.let { it + List(maxOf(0, 3 - it.size)) { 0 } }
                val q = qualifier?.lowercase()
                val rank = when {
                    q == null -> 4
                    q.startsWith("rc") -> 3
                    q.startsWith("beta") -> 2
                    q.startsWith("alpha") -> 1
                    else -> 0
                }
                val qNumber = q?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                return numbers.take(3) + rank + qNumber
            }
            val x = parse(a)
            val y = parse(b)
            for (i in x.indices) {
                val c = x[i].compareTo(y[i])
                if (c != 0) return c
            }
            return 0
        }

        private fun httpGet(url: String): InputStream {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "ADB-Mirror")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("HTTP $code: $url")
            }
            return connection.inputStream
        }
    }
}
