package io.github.eoeo0326.adbmirror.core.data.mirror

import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ClasspathServerJarSource
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyServerLauncher
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerJarAndDeviceTest {
    @Test
    fun bundledServerMatchesGradleProperties() = runBlocking {
        val jar = ClasspathServerJarSource.load()
        assertEquals(System.getProperty("scrcpy.version"), jar.version)
        val sha = MessageDigest.getInstance("SHA-256").digest(jar.bytes).joinToString("") { "%02x".format(it) }
        assertEquals(System.getProperty("scrcpy.sha256"), sha)
    }

    /** `./gradlew :core:data:jvmTest -Padbmirror.device=<serial>`일 때만 실제 기기로 돈다. */
    @Test
    fun mirrorsRealDeviceWhenConfigured() = runBlocking {
        val serial = System.getProperty("adbmirror.device")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val transport = AdbBinaryTransport.locate() ?: error("adb를 찾지 못했습니다")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = MirrorRepositoryImpl(ScrcpyServerLauncher(transport, ClasspathServerJarSource, log = { println(it) }), scope)
            .start(serial, MirrorOptions(maxSize = 720, maxFps = 30, control = true))
        try {
            val packets = withTimeout(10_000) {
                val collected = async { session.packets.take(3).toList() }
                // 캡처가 시작되면 인코더를 재시작해 key frame을 바로 받는다.
                session.requestKeyFrame()
                collected.await()
            }
            assertTrue(packets.any { it.kind == EncodedPacket.Kind.Config })
            val size = session.events.filterIsInstance<SessionEvent.VideoSizeChanged>().first().size
            assertTrue(maxOf(size.width, size.height) <= 720, "max_size 적용: $size")
        } finally {
            session.stop()
            scope.cancel()
        }
        assertEquals(SessionEvent.Ended(null), session.events.filterIsInstance<SessionEvent.Ended>().first())
        assertTrue(transport.devices().any { it.serial == serial })
    }
}
