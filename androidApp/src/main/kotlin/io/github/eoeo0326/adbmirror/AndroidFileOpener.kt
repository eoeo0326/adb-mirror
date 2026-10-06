package io.github.eoeo0326.adbmirror

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.eoeo0326.adbmirror.feature.conversion.FileOpener

/**
 * Android: MediaStore에 올린 파일을 content Uri로 다른 앱(갤러리·뷰어)에 연다.
 * Android 9 이하는 앱 전용 폴더에 두어 Uri가 없으므로 열지 않는다. 폴더 열기는 공통 방법이 없어 지원하지 않는다.
 */
class AndroidFileOpener(private val context: Context) : FileOpener {
    override fun canOpen(file: String, uri: String?) = uri != null

    override fun open(file: String, uri: String?) {
        uri ?: return
        val mime = when (file.substringAfterLast('.').lowercase()) {
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "이 파일을 열 앱이 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    override fun canReveal(file: String) = false

    override fun reveal(file: String) = Unit
}
