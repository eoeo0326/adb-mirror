package io.github.eoeo0326.adbmirror.core.data.storage

/** 앱 저장소의 작은 텍스트 파일 하나. 플랫폼(Desktop·Android)이 구현한다. */
interface TextStore {
    suspend fun read(): String?
    suspend fun write(text: String)
}
