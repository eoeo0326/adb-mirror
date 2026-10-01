package io.github.eoeo0326.adbmirror.core.data.mirror

import io.github.eoeo0326.adbmirror.core.data.scrcpy.LaunchedServers
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyException
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ServerJar
import io.github.eoeo0326.adbmirror.core.data.storage.TextStore
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import io.github.eoeo0326.adbmirror.core.adb.ByteSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrcpyServerLauncherTest {
    private val jar = ServerJar("4.1", byteArrayOf(1, 2, 3))

    @Test
    fun retriesUntilDummyByteThenOpensControl() = runTest {
        val notReady = FakeStream.of(ByteArray(0))
        val video = FakeStream.of(byteArrayOf(0))
        val control = FakeStream.of(ByteArray(0))
        val transport = FakeAdbTransport(ArrayDeque(listOf(notReady, video, control)))
        val launcher = ScrcpyServerLauncher(transport, { jar }, Random(1), retryDelayMs = 1)

        val connection = launcher.launch("S1", MirrorOptions(maxSize = 1280, maxFps = 60, control = true))

        assertTrue(notReady.closed, "준비 전 연결은 닫아야 한다")
        assertEquals(video.stream, connection.video)
        assertEquals(control.stream, connection.control)
        assertEquals(listOf("S1" to ScrcpyServerLauncher.DEVICE_PATH), transport.pushed)
        val name = transport.opened.first()
        assertTrue(Regex("scrcpy_[0-9a-f]{8}").matches(name), name)
        assertEquals(List(3) { name }, transport.opened)

        val args = transport.commands.single()
        assertEquals(listOf("CLASSPATH=${ScrcpyServerLauncher.DEVICE_PATH}", "app_process", "/", "com.genymobile.scrcpy.Server", "4.1"), args.take(5))
        assertTrue("scid=${name.removePrefix("scrcpy_")}" in args)
        assertTrue("control=true" in args && "audio=false" in args && "max_size=1280" in args && "tunnel_forward=true" in args)
    }

    @Test
    fun retriesWhenTransportRefusesBeforeServerListens() = runTest {
        val video = FakeStream.of(byteArrayOf(0))
        val transport = FakeAdbTransport(ArrayDeque(listOf(video))).apply { refuseOpens = 2 }
        val launcher = ScrcpyServerLauncher(transport, { jar }, Random(1), retryDelayMs = 1)

        val connection = launcher.launch("S1", MirrorOptions(maxSize = 1280, maxFps = 60, control = false))

        assertEquals(video.stream, connection.video)
        assertEquals(3, transport.opened.size, "거절 두 번 뒤 세 번째에 연결")
        assertTrue(!transport.process.stopped)
    }

    @Test
    fun noControlSocketWhenControlDisabled() = runTest {
        val transport = FakeAdbTransport(ArrayDeque(listOf(FakeStream.of(byteArrayOf(0)))))
        val connection = ScrcpyServerLauncher(transport, { jar }).launch("S1", MirrorOptions(720, 30, control = false))
        assertNull(connection.control)
        assertTrue("control=false" in transport.commands.single())
    }

    @Test
    fun cancellingDuringConnectCleansUp() = runTest {
        val stuck = FakeStream(object : ByteSource {
            override suspend fun readFully(count: Int): ByteArray = awaitCancellation()
            override suspend fun close() {}
        })
        val transport = FakeAdbTransport(ArrayDeque(listOf(stuck)))
        val job = launch { ScrcpyServerLauncher(transport, { jar }).launch("S1", MirrorOptions(720, 30, control = true)) }
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()
        assertTrue(stuck.closed, "읽던 영상 소켓을 닫아야 한다")
        assertTrue(transport.process.stopped, "서버를 멈춰야 한다")
    }

    @Test
    fun controlSocketFailureClosesVideoAndStopsServer() = runTest {
        val video = FakeStream.of(byteArrayOf(0))
        val transport = FakeAdbTransport(ArrayDeque(listOf(video))).apply { failOpenAt = 1 }
        assertFailsWith<IllegalStateException> {
            ScrcpyServerLauncher(transport, { jar }).launch("S1", MirrorOptions(720, 30, control = true))
        }
        assertTrue(video.closed)
        assertTrue(transport.process.stopped)
    }

    @Test
    fun givesUpAndStopsServerWhenNeverReady() = runTest {
        val transport = FakeAdbTransport(ArrayDeque(List(3) { FakeStream.of(ByteArray(0)) }))
        val launcher = ScrcpyServerLauncher(transport, { jar }, retryDelayMs = 1, maxAttempts = 3)
        assertFailsWith<ScrcpyException> { launcher.launch("S1", MirrorOptions(720, 30, control = true)) }
        assertTrue(transport.process.stopped)
    }

    private class MemStore(var text: String? = null) : TextStore {
        override suspend fun read() = text
        override suspend fun write(text: String) { this.text = text }
    }

    @Test
    fun launchedServerIsRecordedUntilStopped() = runTest {
        val store = MemStore()
        val transport = FakeAdbTransport(ArrayDeque(listOf(FakeStream.of(byteArrayOf(0)))))
        val connection = ScrcpyServerLauncher(transport, { jar }, Random(1), retryDelayMs = 1, launched = LaunchedServers(store))
            .launch("S1", MirrorOptions(720, 30, control = false))
        val scid = transport.opened.first().removePrefix("scrcpy_")
        assertEquals("S1 $scid", store.text, "띄운 서버를 기기와 함께 기록한다")
        connection.process.stop()
        assertTrue(transport.process.stopped)
        assertEquals("", store.text, "정상 종료하면 기록을 지운다")
    }

    @Test
    fun leftoversOnThisDeviceAreKilledBeforeLaunch() = runTest {
        // 무선 기기는 포트가 바뀌어도 같은 호스트면 같은 기기로 본다. 다른 기기의 기록은 남긴다.
        val store = MemStore("192.168.0.9 0000beef\n192.168.0.9 1234abcd\nS2 5555aaaa\nnot-a-record")
        val transport = FakeAdbTransport(ArrayDeque(listOf(FakeStream.of(byteArrayOf(0))))).apply {
            shellReply = { error("exit 1") } // pkill이 못 찾으면 실패로 끝나도 계속한다
        }
        ScrcpyServerLauncher(transport, { jar }, Random(1), retryDelayMs = 1, launched = LaunchedServers(store))
            .launch("192.168.0.9:41234", MirrorOptions(720, 30, control = false))
        assertEquals(
            listOf(listOf("pkill", "-f", "'scid=[0]000beef'"), listOf("pkill", "-f", "'scid=[1]234abcd'")),
            transport.shellCommands,
        )
        val scid = transport.opened.first().removePrefix("scrcpy_")
        assertEquals("S2 5555aaaa\n192.168.0.9 $scid", store.text, "이 기기의 남은 기록만 지운다")
    }

    @Test
    fun runningSessionOfThisProcessIsNotKilled() = runTest {
        val store = MemStore()
        val launched = LaunchedServers(store)
        val transport = FakeAdbTransport(ArrayDeque(List(2) { FakeStream.of(byteArrayOf(0)) }))
        val launcher = ScrcpyServerLauncher(transport, { jar }, Random(1), retryDelayMs = 1, launched = launched)
        launcher.launch("S1", MirrorOptions(720, 30, control = false))
        launcher.launch("S1", MirrorOptions(720, 30, control = false))
        assertEquals(emptyList(), transport.shellCommands, "실행 중인 세션은 남은 서버가 아니다")
        assertEquals(2, store.text!!.lines().size)
    }

    @Test
    fun failedLaunchRemovesRecord() = runTest {
        val store = MemStore()
        val transport = FakeAdbTransport(ArrayDeque(List(3) { FakeStream.of(ByteArray(0)) }))
        assertFailsWith<ScrcpyException> {
            ScrcpyServerLauncher(transport, { jar }, retryDelayMs = 1, maxAttempts = 3, launched = LaunchedServers(store))
                .launch("S1", MirrorOptions(720, 30, control = true))
        }
        assertTrue(transport.process.stopped)
        assertEquals("", store.text)
    }
}
