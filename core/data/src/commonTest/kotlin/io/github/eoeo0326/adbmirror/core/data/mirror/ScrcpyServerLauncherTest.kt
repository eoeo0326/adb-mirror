package io.github.eoeo0326.adbmirror.core.data.mirror

import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyException
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ServerJar
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
}
