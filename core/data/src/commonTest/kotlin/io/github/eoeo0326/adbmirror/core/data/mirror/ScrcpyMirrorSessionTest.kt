package io.github.eoeo0326.adbmirror.core.data.mirror

import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.adb.ByteSource
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyConnection
import io.github.eoeo0326.adbmirror.core.data.scrcpy.VideoStreamParser
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrcpyMirrorSessionTest {
    private fun u32(v: Long) = ByteArray(4) { ((v ushr (24 - 8 * it)) and 0xFF).toByte() }
    private fun u64(v: ULong) = ByteArray(8) { ((v shr (56 - 8 * it)) and 0xFFUL).toByte() }

    private val stream = "Pixel".encodeToByteArray().copyOf(64) + u32(VideoStreamParser.CODEC_H264) +
        u32(0x80000000) + u32(340) + u32(720) +
        u64(1UL shl 62) + u32(3) + byteArrayOf(0, 0, 1) +
        u64((1UL shl 61) or 5UL) + u32(2) + byteArrayOf(0x65, 1)

    /** 스트림을 다 보낸 뒤 [release]가 끝날 때까지 다음 읽기를 막는 소스. */
    private class HoldingSource(data: ByteArray, private val release: CompletableDeferred<Unit>) : ByteSource {
        private val inner = ByteArraySource(data)
        override suspend fun readFully(count: Int): ByteArray {
            if (inner.remaining == 0) release.await()
            return inner.readFully(count)
        }
        override suspend fun close() = inner.close()
    }

    @Test
    fun emitsEventsAndPacketsThenEndsWhenStreamCloses() = runTest {
        val video = FakeStream(ByteArraySource(stream))
        val process = FakeProcess()
        val session = ScrcpyMirrorSession("S1", ScrcpyConnection(video.stream, null, process), backgroundScope)
        backgroundScope.launch { session.packets.collect {} } // 화면처럼 패킷을 받는 쪽

        val events = session.events.take(3).toList()
        assertEquals(
            listOf(SessionEvent.DeviceName("Pixel"), SessionEvent.VideoSizeChanged(VideoSize(340, 720)), SessionEvent.Ended("영상 스트림이 끊겼습니다")),
            events,
        )
        assertTrue(video.closed)
        assertTrue(process.stopped)
    }

    @Test
    fun packetsWaitForFirstSubscriber() = runTest {
        val video = FakeStream(ByteArraySource(stream))
        val session = ScrcpyMirrorSession("S1", ScrcpyConnection(video.stream, null, FakeProcess()), backgroundScope)
        // 구독자가 없어도 이름·해상도는 오지만, 첫 패킷은 구독자를 기다린다(스트림 끝까지 읽지 않음).
        assertEquals(SessionEvent.DeviceName("Pixel"), session.events.first())
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf(SessionEvent.DeviceName("Pixel"), SessionEvent.VideoSizeChanged(VideoSize(340, 720))),
            session.events.replayCache,
        )
        val first = session.packets.first()
        assertEquals(EncodedPacket.Kind.Config, first.kind)
    }

    @Test
    fun packetsReachSubscriber() = runTest {
        val hold = CompletableDeferred<Unit>()
        val video = FakeStream(HoldingSource(stream, hold))
        val packets = mutableListOf<EncodedPacket>()
        val session = ScrcpyMirrorSession("S1", ScrcpyConnection(video.stream, null, FakeProcess()), backgroundScope)
        val collector = launch { session.packets.take(2).toList(packets) }
        collector.join()
        assertEquals(listOf(EncodedPacket.Kind.Config, EncodedPacket.Kind.KeyFrame), packets.map { it.kind })
        assertEquals(5L, packets[1].ptsUs)
        hold.complete(Unit)
    }

    @Test
    fun touchAndKeyFrameGoToControlSocket() = runTest {
        val hold = CompletableDeferred<Unit>()
        val control = FakeStream.of(ByteArray(0))
        val session = ScrcpyMirrorSession(
            "S1",
            ScrcpyConnection(FakeStream(HoldingSource(stream, hold)).stream, control.stream, FakeProcess()),
            backgroundScope,
        )
        session.events.filterIsInstance<SessionEvent.VideoSizeChanged>().first() // 캡처 시작(패킷 구독 전에도 옴)
        session.sendTouch(TouchEvent(TouchAction.Down, 1, 2, VideoSize(340, 720)))
        session.requestKeyFrame()
        assertEquals(listOf(32, 1), control.sink.writes.map { it.size })
        assertEquals(17, control.sink.writes[1][0].toInt())

        session.stop()
        assertEquals(SessionEvent.Ended(null), session.events.filterIsInstance<SessionEvent.Ended>().first())
        assertTrue(control.closed)
        session.sendTouch(TouchEvent(TouchAction.Up, 1, 2, VideoSize(340, 720)))
        assertEquals(2, control.sink.writes.size, "끝난 세션은 컨트롤을 보내지 않는다")
    }

    @Test
    fun keyFrameRequestWaitsForCaptureStart() = runTest {
        val control = FakeStream.of(ByteArray(0))
        // 세션 패킷이 오기 전(헤더만) 상태에서 멈춘 스트림
        val headerOnly = stream.copyOf(64 + 4)
        val hold = CompletableDeferred<Unit>()
        val session = ScrcpyMirrorSession(
            "S1",
            ScrcpyConnection(FakeStream(HoldingSource(headerOnly, hold)).stream, control.stream, FakeProcess()),
            backgroundScope,
        )
        backgroundScope.launch { session.packets.collect {} }
        val request = launch { session.requestKeyFrame() }
        testScheduler.advanceUntilIdle()
        assertTrue(control.sink.writes.isEmpty(), "캡처 시작 전에는 RESET_VIDEO를 보내지 않는다")
        session.stop()
        request.join()
        assertTrue(control.sink.writes.isEmpty(), "끝난 세션은 대기를 풀지만 보내지 않는다")
    }

    @Test
    fun serverExitEndsSession() = runTest {
        val hold = CompletableDeferred<Unit>()
        val process = FakeProcess()
        val session = ScrcpyMirrorSession("S1", ScrcpyConnection(FakeStream(HoldingSource(stream, hold)).stream, null, process), backgroundScope)
        process.exit.complete(137)
        val ended = session.events.filterIsInstance<SessionEvent.Ended>().first()
        assertEquals(SessionEvent.Ended("scrcpy 서버가 종료됐습니다 (exit 137)"), ended)
    }
}
