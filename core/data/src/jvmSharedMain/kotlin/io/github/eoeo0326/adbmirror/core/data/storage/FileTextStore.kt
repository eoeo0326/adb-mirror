package io.github.eoeo0326.adbmirror.core.data.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 앱 저장소의 작은 텍스트 파일. 읽지 못하면 없는 것으로 본다. */
class FileTextStore(private val file: File) : TextStore {
    override suspend fun read(): String? = withContext(Dispatchers.IO) { runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        Unit
    }
}
