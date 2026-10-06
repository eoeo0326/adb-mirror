package io.github.eoeo0326.adbmirror.core.data.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * 사진·동영상을 공유 저장소(`Pictures/ADB Mirror`·`Movies/ADB Mirror`)에 올린다. 권한이 필요 없다.
 * Android 9 이하는 MediaStore에 쓰려면 저장소 권한이 필요해서, 대신 앱 전용 외부 폴더(`Android/data/…`)에 둔다.
 */
class SharedMedia(private val context: Context) {
    enum class Kind(val directory: String) { Image(Environment.DIRECTORY_PICTURES), Video(Environment.DIRECTORY_MOVIES) }

    /** 올린 파일. [location]은 보여줄 위치, [uri]는 다른 앱으로 열 수 있는 content Uri(앱 전용 폴더에 둔 경우 null). */
    data class Published(val location: String, val uri: String?)

    /** [name]으로 만들고 [write]로 내용을 쓴다. 같은 이름이 있으면 MediaStore가 이름을 바꾼다. */
    fun publish(kind: Kind, mime: String, name: String, write: (OutputStream) -> Unit): Published =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishToMediaStore(kind, mime, name, write) else publishToAppFolder(kind, name, write)

    private fun publishToMediaStore(kind: Kind, mime: String, name: String, write: (OutputStream) -> Unit): Published {
        val resolver = context.contentResolver
        val collection = when (kind) {
            Kind.Image -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.Video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relative = "${kind.directory}/$FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            // 다 쓸 때까지 다른 앱(갤러리)에 보이지 않게 한다.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("$relative 에 파일을 만들지 못했습니다")
        try {
            (resolver.openOutputStream(uri) ?: error("$relative 에 쓰지 못했습니다")).use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        val saved = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return Published("$relative/$saved", uri.toString())
    }

    private fun publishToAppFolder(kind: Kind, name: String, write: (OutputStream) -> Unit): Published {
        val dir = File(context.getExternalFilesDir(kind.directory) ?: File(context.filesDir, kind.directory), FOLDER).apply { mkdirs() }
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        val file = generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) name else "${base}_$n.$ext") }
            .first { it.createNewFile() }
        try {
            file.outputStream().use(write)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return Published(file.absolutePath, null)
    }

    private companion object {
        const val FOLDER = "ADB Mirror"
    }
}
