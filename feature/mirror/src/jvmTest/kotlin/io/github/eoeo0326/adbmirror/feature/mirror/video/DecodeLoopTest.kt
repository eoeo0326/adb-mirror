package io.github.eoeo0326.adbmirror.feature.mirror.video

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 코루틴 디버그 모드가 붙이는 " @coroutine#N"을 뗀 스레드 이름 */
private fun threadName() = Thread.currentThread().name.substringBefore(" @")

class DecodeLoopTest {
    private class RecordingDecoder : FrameDecoder {
        val decodeThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        @Volatile var closeThread: String? = null
        @Volatile var decodingWhenClosed = false
        @Volatile private var decoding = false
        val decodedOnce = CompletableDeferred<Unit>()

        override fun decode(data: ByteArray, sink: FfmpegH264Decoder.FrameSink) {
            decoding = true
            decodeThreads += threadName()
            Thread.sleep(20) // 네이티브 디코딩처럼 잠깐 블로킹
            decoding = false
            decodedOnce.complete(Unit)
        }

        override fun close() {
            decodingWhenClosed = decoding
            closeThread = threadName()
        }
    }

    @Test
    fun cancellationClosesDecoderOnDecoderThreadAfterDecodingStops() = runBlocking {
        val packets = MutableSharedFlow<EncodedPacket>(extraBufferCapacity = 64)
        val decoder = RecordingDecoder()
        val job = launch(Dispatchers.Default) { decodeOnDedicatedThread(packets, { decoder }) { _, _, _ -> } }
        withTimeout(5_000) {
            while (packets.subscriptionCount.value == 0) kotlinx.coroutines.delay(5)
            repeat(10) { packets.emit(EncodedPacket(EncodedPacket.Kind.Frame, it.toLong(), ByteArray(1))) }
            decoder.decodedOnce.await()
        }
        job.cancelAndJoin()

        assertEquals(setOf("video-decoder"), decoder.decodeThreads)
        assertEquals("video-decoder", decoder.closeThread, "close는 디코딩하던 스레드에서 돌아야 한다")
        assertFalse(decoder.decodingWhenClosed, "디코딩 중에 닫으면 안 된다")
        // 스레드도 내려가야 한다(누수 방지)
        Thread.sleep(100)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "video-decoder" && it.isAlive })
    }
}
