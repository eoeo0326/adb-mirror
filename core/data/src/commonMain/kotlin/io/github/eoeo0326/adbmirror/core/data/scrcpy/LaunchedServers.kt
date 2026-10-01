package io.github.eoeo0326.adbmirror.core.data.scrcpy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 앱 저장소의 작은 텍스트 파일 하나. 플랫폼(Desktop·Android)이 구현한다. */
interface TextStore {
    suspend fun read(): String?
    suspend fun write(text: String)
}

/**
 * 이 앱이 띄우고 아직 정상 종료하지 못한 scrcpy 서버(scid). 앱이 강제 종료·크래시되면 기기 쪽 adbd가
 * 서버를 끝내지 않는 경우가 있어, 다음에 서버를 띄우기 전에 남은 것을 정리하는 데 쓴다.
 * 무선 기기는 다시 켤 때 포트(serial)가 바뀔 수 있어 기기 구분 없이 scid만 기록한다.
 */
class LaunchedServers(private val store: TextStore, private val maxEntries: Int = 32) {
    private val lock = Mutex()

    suspend fun add(scid: String) = lock.withLock { save((load() + scid).takeLast(maxEntries)) }

    suspend fun remove(scid: String) = lock.withLock { save(load() - scid) }

    /** 기록된 scid를 모두 돌려주고 비운다. */
    suspend fun takeAll(): List<String> = lock.withLock { load().also { if (it.isNotEmpty()) save(emptyList()) } }

    private suspend fun load(): List<String> =
        store.read().orEmpty().lines().map { it.trim() }.filter { SCID.matches(it) }.distinct()

    private suspend fun save(scids: List<String>) = store.write(scids.joinToString("\n"))

    private companion object {
        val SCID = Regex("[0-9a-f]{8}")
    }
}
