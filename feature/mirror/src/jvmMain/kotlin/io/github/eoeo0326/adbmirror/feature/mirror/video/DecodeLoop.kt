package io.github.eoeo0326.adbmirror.feature.mirror.video

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** 한 스레드에서만 쓰는 영상 디코더. */
interface FrameDecoder : AutoCloseable {
    fun decode(data: ByteArray, sink: FfmpegH264Decoder.FrameSink)
}

/**
 * [packets]를 전용 스레드 하나에서 디코딩한다. 취소되면 디코더를 **그 스레드에서** 닫고,
 * 닫기가 끝난 뒤에야 스레드를 내린다. 스레드를 먼저 내리면 close가 다른 스레드로 넘어가
 * 디코딩 중인 네이티브 자원을 해제할 수 있다(use-after-free).
 */
suspend fun decodeOnDedicatedThread(
    packets: Flow<EncodedPacket>,
    newDecoder: () -> FrameDecoder,
    onFrame: FfmpegH264Decoder.FrameSink,
) {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "video-decoder").apply { isDaemon = true } }
    val dispatcher = executor.asCoroutineDispatcher()
    try {
        withContext(dispatcher) {
            newDecoder().use { decoder -> packets.collect { decoder.decode(it.data, onFrame) } }
        }
    } finally {
        dispatcher.close() // withContext가 끝났으니 디코더는 이미 닫혔다. 이제 스레드를 내린다.
    }
}
