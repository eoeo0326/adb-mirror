package io.github.eoeo0326.adbmirror.core.data.screenshot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import io.github.eoeo0326.adbmirror.core.data.media.SharedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 클립보드에는 앱 캐시의 PNG를 [FileProvider] URI로 올린다(붙여 넣는 앱이 읽을 권한은 시스템이 준다).
 * 저장은 `Pictures/ADB Mirror`. Android에는 저장 폴더 설정이 없어 [save]의 dir은 쓰지 않는다.
 */
class AndroidScreenshotSink(
    private val context: Context,
    /** 앱 매니페스트에 선언한 FileProvider authority. 캐시의 `clipboard/` 폴더를 공유해야 한다. */
    private val fileProviderAuthority: String,
    private val media: SharedMedia = SharedMedia(context),
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ScreenshotSink {
    override suspend fun copyToClipboard(png: ByteArray) = withContext(Dispatchers.IO) {
        // 같은 이름에 덮어쓰면 전에 붙여 넣은 앱이 아직 읽는 중일 수 있어 매번 새 이름으로 쓰고 예전 것은 지운다.
        val dir = File(context.cacheDir, CLIPBOARD_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "screenshot_${now().format(STAMP)}.png").apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: error("클립보드를 쓸 수 없습니다")
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "스크린샷", uri))
    }

    override suspend fun save(png: ByteArray, dir: String?, baseName: String): String = withContext(Dispatchers.IO) {
        media.publish(SharedMedia.Kind.Image, "image/png", "${baseName}_${now().format(STAMP)}.png") { it.write(png) }.location
    }

    companion object {
        /** 캐시 안 클립보드 PNG 폴더. FileProvider 경로 설정과 맞춘다. */
        const val CLIPBOARD_DIR = "clipboard"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
