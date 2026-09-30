package io.github.eoeo0326.adbmirror.core.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ConnectionRegistryTest {
    private class Conn(val id: Int)

    private val closed = mutableListOf<Int>()
    private val registry = ConnectionRegistry<Conn> { closed += it.id }
    private var opens = 0
    private val device = AdbDevice("h:1", "device", "SM-N976N")

    @Test
    fun concurrentOpensForSameDeviceOpenOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val open: suspend () -> Pair<Conn, AdbDevice> = { opens++; gate.await(); Conn(opens) to device }
        val a = async { registry.getOrOpen("h:1", open) }
        val b = async { registry.getOrOpen("h:1", open) }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        assertEquals(device, a.await())
        assertEquals(device, b.await())
        assertEquals(1, opens, "두 번째 요청은 첫 연결을 기다려 그대로 쓴다")
        assertEquals(1, registry.entries.value.size)
        assertEquals(emptyList(), closed)
    }

    @Test
    fun cancelledCallerStillRegistersOpenedConnection() = runTest {
        val gate = CompletableDeferred<Unit>()
        val call = async { registry.getOrOpen("h:1") { gate.await(); Conn(7) to device } }
        testScheduler.advanceUntilIdle()
        call.cancel()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        // 호출한 쪽은 취소됐어도 연 연결은 등록돼 나중에 끊을 수 있다(새지 않음)
        assertEquals(7, registry["h:1"]!!.id)
        registry.remove("h:1")
        assertEquals(listOf(7), closed)
    }

    @Test
    fun failedOpenRegistersNothing() = runTest {
        assertFailsWith<AdbException> { registry.getOrOpen("h:1") { throw AdbException("연결하지 못했습니다") } }
        assertNull(registry["h:1"])
        val c = Conn(1)
        registry.getOrOpen("h:1") { c to device }
        assertSame(c, registry["h:1"])
    }

    @Test
    fun removeClosesOnlyOnce() = runTest {
        registry.getOrOpen("h:1") { Conn(3) to device }
        registry.remove("h:1")
        registry.remove("h:1")
        assertEquals(listOf(3), closed)
        assertNull(registry["h:1"])
    }
}
