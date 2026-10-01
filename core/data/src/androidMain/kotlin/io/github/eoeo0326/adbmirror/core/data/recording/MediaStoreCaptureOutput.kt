package io.github.eoeo0326.adbmirror.core.data.recording

import android.content.Context
import io.github.eoeo0326.adbmirror.core.data.media.SharedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android: 녹화는 앱 캐시에 쓰고, 끝나면 `Movies/ADB Mirror`에 복사해 올린다. 변환(GIF·WebP)은 캐시의 녹화 파일을
 * 읽어 `Pictures/ADB Mirror`에 올린다. 캐시의 녹화는 변환에만 쓰므로 하루 지난 것은 다음 녹화 때 지운다(시스템도 지울 수 있음).
 */
class MediaStoreCaptureOutput(
    private val context: Context,
    private val media: SharedMedia = SharedMedia(context),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CaptureOutput {
    override fun recordingDir(outputDir: String?): File {
        val dir = File(context.cacheDir, "recordings")
        dir.listFiles()?.filter { nowMs() - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        return dir
    }

    override suspend fun recordingSaved(files: List<File>): List<String> = withContext(Dispatchers.IO) {
        files.map { file -> media.publish(SharedMedia.Kind.Video, "video/mp4", file.name) { out -> file.inputStream().use { it.copyTo(out) } } }
    }

    override fun conversionTempDir(source: File): File = context.cacheDir

    override suspend fun publishAnimation(tmp: File, source: File, ext: String): String = withContext(Dispatchers.IO) {
        try {
            media.publish(SharedMedia.Kind.Image, "image/$ext", "${source.nameWithoutExtension}.$ext") { out -> tmp.inputStream().use { it.copyTo(out) } }
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val KEEP_MS = 24 * 60 * 60 * 1000L
    }
}
