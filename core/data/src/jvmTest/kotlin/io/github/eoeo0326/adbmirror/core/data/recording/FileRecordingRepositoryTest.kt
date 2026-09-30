package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileRecordingRepositoryTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val config = EncodedPacket(EncodedPacket.Kind.Config, null, b(0, 0, 0, 1, 0x67, 0x42, 0, 0x1f, 0, 0, 0, 1, 0x68, 0xce), VideoSize(340, 720))
    private fun key(pts: Long) = EncodedPacket(EncodedPacket.Kind.KeyFrame, pts, b(0, 0, 0, 1, 0x65, 1))
    private fun frame(pts: Long) = EncodedPacket(EncodedPacket.Kind.Frame, pts, b(0, 0, 0, 1, 0x41, 9))

    private class Session(override val serial: String) : MirrorSession {
        // replay·버퍼 없음: 구독자가 없을 때 보낸 패킷은 사라진다(실제 세션과 같음)
        val flow = MutableSharedFlow<EncodedPacket>()
        override val packets: Flow<EncodedPacket> = flow
        override val events: Flow<SessionEvent> = emptyFlow()
        override suspend fun sendTouch(event: TouchEvent) {}
        override suspend fun requestKeyFrame() {}
        override suspend fun stop() {}
    }

    private val home = Files.createTempDirectory("adbmirror-rec").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = FileRecordingRepository(scope, home) { LocalDateTime.of(2026, 9, 30, 9, 8, 7) }

    @AfterTest fun tearDown() = scope.cancel()

    @Test
    fun recordsFromPacketsSentRightAfterStart() = runBlocking {
        val session = Session("192.168.0.5:5555")
        val dir = File(home, "out").path
        repo.start(session, dir)
        // start가 반환됐으면 이미 구독 중이어야 한다(바로 보낸 config를 놓치면 파일이 생기지 않음)
        session.flow.emit(config)
        session.flow.emit(key(0))
        session.flow.emit(frame(500_000))
        session.flow.emit(frame(1_000_000))
        val recording = repo.stop(session.serial)!!
        assertEquals(listOf(File(dir, "adb-mirror_192.168.0.5_5555_20260930_090807.mp4").absolutePath), recording.files)
        assertEquals(1000, recording.durationMs)
        assertTrue(File(recording.files.single()).length() > 0)
    }

    @Test
    fun rotationAddsPartSuffix() = runBlocking {
        val session = Session("A")
        repo.start(session, home.path)
        session.flow.emit(config)
        session.flow.emit(key(0))
        session.flow.emit(EncodedPacket(EncodedPacket.Kind.Config, null, config.data, VideoSize(720, 340)))
        session.flow.emit(key(100_000))
        val names = repo.stop("A")!!.files.map { File(it).name }
        assertEquals(listOf("adb-mirror_A_20260930_090807.mp4", "adb-mirror_A_20260930_090807_part2.mp4"), names)
    }

    @Test
    fun oneRecordingPerDeviceAndStopWithoutStartIsNull() = runBlocking {
        val session = Session("A")
        repo.start(session, home.path)
        assertFailsWith<IllegalStateException> { repo.start(session, home.path) }
        repo.start(Session("B"), home.path) // 다른 기기는 따로
        assertNull(repo.stop("C"))
        assertEquals(emptyList(), repo.stop("A")!!.files) // 아무것도 오지 않은 녹화는 파일 없음
        assertNull(repo.stop("A"))
    }

    @Test
    fun existingFileIsNotOverwritten() = runBlocking {
        File(home, "adb-mirror_A_20260930_090807.mp4").writeText("old")
        val session = Session("A")
        repo.start(session, home.path)
        session.flow.emit(config)
        session.flow.emit(key(0))
        assertEquals("adb-mirror_A_20260930_090807_2.mp4", File(repo.stop("A")!!.files.single()).name)
        assertEquals("old", File(home, "adb-mirror_A_20260930_090807.mp4").readText())
    }
}
