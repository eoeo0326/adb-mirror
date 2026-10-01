package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.data.storage.TextStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 이 앱이 띄우고 아직 정상 종료하지 못한 scrcpy 서버(기기 + scid). 앱이 강제 종료·크래시되면 기기 쪽 adbd가
 * 서버를 끝내지 않는 경우가 있어, 다음에 그 기기에 서버를 띄우기 전에 남은 것을 정리하는 데 쓴다.
 * 무선 기기는 무선 디버깅을 다시 켜면 포트가 바뀌므로 `호스트:포트` serial은 호스트만으로 기기를 구분한다.
 * 한 줄에 `<기기> <scid>` 하나.
 */
class LaunchedServers(private val store: TextStore, private val maxEntries: Int = 32) {
    private data class Entry(val device: String, val scid: String)

    private val lock = Mutex()

    /** 이 프로세스에서 띄워 아직 실행 중인 서버. 정리 대상에서 뺀다. */
    private val active = mutableSetOf<String>()

    suspend fun add(serial: String, scid: String) = lock.withLock {
        active += scid
        save((load().filter { it.scid != scid } + Entry(deviceKey(serial), scid)).takeLast(maxEntries))
    }

    suspend fun remove(scid: String) = lock.withLock {
        active -= scid
        save(load().filter { it.scid != scid })
    }

    /** [serial] 기기에 남은(이 프로세스가 실행 중인 것이 아닌) scid를 돌려주고 기록에서 지운다. */
    suspend fun takeLeftovers(serial: String): List<String> = lock.withLock {
        val device = deviceKey(serial)
        val (mine, rest) = load().partition { it.device == device && it.scid !in active }
        if (mine.isNotEmpty()) save(rest)
        mine.map { it.scid }
    }

    private suspend fun load(): List<Entry> = store.read().orEmpty().lines().mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size == 2 && parts[0].isNotEmpty() && SCID.matches(parts[1])) Entry(parts[0], parts[1]) else null
    }.distinctBy { it.scid }

    private suspend fun save(entries: List<Entry>) = store.write(entries.joinToString("\n") { "${it.device} ${it.scid}" })

    private companion object {
        val SCID = Regex("[0-9a-f]{8}")
        val HOST_PORT = Regex("(.+):\\d+")

        fun deviceKey(serial: String): String = (HOST_PORT.matchEntire(serial)?.groupValues?.get(1) ?: serial).replace(' ', '_')
    }
}
