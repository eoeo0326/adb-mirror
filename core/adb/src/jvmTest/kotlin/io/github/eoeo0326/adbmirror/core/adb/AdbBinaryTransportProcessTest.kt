package io.github.eoeo0326.adbmirror.core.adb

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 가짜 adb 셸 스크립트로 실제 프로세스 동작(취소·출력 해석)을 확인한다. POSIX 전용. */
class AdbBinaryTransportProcessTest {
    private val posix = !System.getProperty("os.name").startsWith("Windows")

    private fun fakeAdb(body: String): Pair<AdbBinaryTransport, File> {
        val dir = Files.createTempDirectory("fake-adb").toFile()
        val script = File(dir, "adb")
        // 받은 명령을 calls 파일에 남기고, 진짜 adb처럼 start-server는 바로 끝난다(trackDevices가 먼저 부른다).
        script.writeText("#!/bin/sh\necho \"\$1\" >> \"\$(dirname \"\$0\")/calls\"\n[ \"\$1\" = start-server ] && exit 0\n$body\n")
        script.setExecutable(true)
        return AdbBinaryTransport(script) to dir
    }

    @Test
    fun cancelledCommandKillsProcess() = runBlocking {
        if (!posix) return@runBlocking
        val (transport, dir) = fakeAdb("echo \$\$ > \"${'$'}(dirname \"$0\")/pid\"; exec sleep 30")
        val started = System.nanoTime()
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(500) { transport.shell("S1", listOf("true")) }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 3_000, "취소 후 곧바로 끝나야 한다: ${elapsedMs}ms")
        val pid = File(dir, "pid").readText().trim().toLong()
        Thread.sleep(200)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "adb 프로세스가 남으면 안 된다")
    }

    @Test
    fun failingCommandReportsStderr() = runBlocking {
        if (!posix) return@runBlocking
        val (transport, _) = fakeAdb("echo 'error: device not found' >&2; exit 1")
        val e = assertFailsWith<AdbException> { transport.shell("S1", listOf("ls")) }
        assertTrue("device not found" in e.message!!)
    }

    @Test
    fun trackDevicesParsesMessages() = runBlocking {
        if (!posix) return@runBlocking
        val (transport, _) = fakeAdb("printf '0013R3CM90LKDDJ\\tdevice\\n'; exec sleep 30")
        val list = withTimeout(5_000) { transport.trackDevices().first() }
        assertEquals(listOf(AdbDevice("R3CM90LKDDJ", "device")), list)
    }

    @Test
    fun trackDevicesFailsInsteadOfHangingOnBadOutput() = runBlocking {
        if (!posix) return@runBlocking
        val (transport, _) = fakeAdb("printf 'zzzz'; exec sleep 30")
        assertFailsWith<AdbException> { withTimeout(5_000) { transport.trackDevices().first() } }
        Unit
    }

    @Test
    fun trackDevicesStartsServerFirstAndIgnoresStderr() = runBlocking {
        if (!posix) return@runBlocking
        // 서버를 띄우는 adb처럼 stderr에 안내를 찍어도 track 메시지만 해석한다.
        val (transport, dir) = fakeAdb("echo '* daemon not running; starting now at tcp:5037' >&2; printf '0013R3CM90LKDDJ\\tdevice\\n'; exec sleep 30")
        val list = withTimeout(5_000) { transport.trackDevices().first() }
        assertEquals(listOf(AdbDevice("R3CM90LKDDJ", "device")), list)
        assertEquals(listOf("start-server", "track-devices"), File(dir, "calls").readLines())
    }
}
