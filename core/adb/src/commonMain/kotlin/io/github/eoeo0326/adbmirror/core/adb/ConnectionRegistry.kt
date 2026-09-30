package io.github.eoeo0326.adbmirror.core.adb

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 기기(serial)마다 연결 하나를 둔다. 같은 기기를 동시에 여러 번 열어도 [open]은 한 번만 불리고,
 * 연 연결은 반드시 등록되거나 닫힌다(도중에 취소돼도 새지 않음).
 */
class ConnectionRegistry<C : Any>(private val close: suspend (C) -> Unit) {
    class Entry<C>(val connection: C, val device: AdbDevice)

    private val lock = Mutex()
    private val _entries = MutableStateFlow<Map<String, Entry<C>>>(emptyMap())
    val entries: StateFlow<Map<String, Entry<C>>> = _entries.asStateFlow()

    operator fun get(serial: String): C? = _entries.value[serial]?.connection

    /**
     * 이미 연결돼 있으면 그 기기를, 아니면 [open]으로 연결해 등록한다. 연결을 여는 동안에는 취소하지 않는다
     * (연결 시간은 [open] 쪽 제한 시간으로 끊는다). [open]이 실패하면 아무것도 등록하지 않는다.
     */
    suspend fun getOrOpen(serial: String, open: suspend () -> Pair<C, AdbDevice>): AdbDevice =
        withContext(NonCancellable) {
            lock.withLock {
                _entries.value[serial]?.let { return@withLock it.device }
                val (connection, device) = open()
                _entries.update { it + (serial to Entry(connection, device)) }
                device
            }
        }

    suspend fun remove(serial: String) = withContext(NonCancellable) {
        val removed = lock.withLock {
            val entry = _entries.value[serial]
            if (entry != null) _entries.update { it - serial }
            entry
        }
        removed?.let { runCatching { close(it.connection) } }
        Unit
    }
}
