package io.github.eoeo0326.adbmirror.feature.mirror.video

import android.util.Log
import android.view.Surface
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * 세션 패킷을 Surface에 그리는 디코딩 루프. Surface가 생기면 [attach], 없어지면 [detach]한다.
 *
 * - 디코딩은 전용 스레드 하나에서 하고, 코덱도 그 스레드에서 만들고 닫는다.
 * - [detach]는 코덱이 Surface를 놓을 때까지 기다린 뒤 돌아간다(SurfaceHolder.Callback.surfaceDestroyed 계약).
 * - 붙을 때마다 key frame을 요청한다. 앞선 config·key frame은 이미 지나갔을 수 있어서다.
 * - config에 실린 크기가 바뀌면(회전) 코덱을 새로 만든다. 코덱이 준비되기 전의 프레임은 버린다.
 */
class SurfaceDecodeController(private val session: MirrorSession) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "video-decoder").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private var job: Job? = null

    /** 초당 그린 프레임을 로그로(`adb logcat -s adb-mirror`). 측정할 때만 켠다. */
    var logFps: Boolean = false

    /** 영상을 더 그릴 수 없을 때(디코더를 계속 만들지 못함) 사용자에게 보일 문구. 디코딩 스레드에서 불린다. */
    var onFatal: (String) -> Unit = {}

    fun attach(surface: Surface) {
        detach()
        job = scope.launch {
            val loop = PacketDecodeLoop(
                newDecoder = { size -> MediaCodecH264Decoder(surface, size) },
                requestKeyFrame = { launch { session.requestKeyFrame() } },
                nowMs = { System.nanoTime() / 1_000_000 },
                onError = { Log.w("adb-mirror", "디코더 오류, key frame을 다시 받아 이어 갑니다: ${it.message}") },
            )
            var frames = 0
            var windowStart = System.nanoTime()
            try {
                loop.start()
                session.packets.collect { packet ->
                    if (loop.accept(packet) && logFps) {
                        frames++
                        val now = System.nanoTime()
                        if (now - windowStart >= 1_000_000_000L) {
                            Log.i("adb-mirror", "decode fps=$frames")
                            frames = 0
                            windowStart = now
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 디코더 오류는 루프가 복구한다. 여기까지 오면 디코더를 계속 만들지 못한 것이다.
                Log.e("adb-mirror", "영상 디코딩을 멈췄습니다: ${e.message}")
                onFatal(e.message ?: "영상을 표시할 수 없습니다")
            } finally {
                loop.close()
            }
        }
    }

    /** 디코딩을 멈추고 코덱이 Surface를 놓을 때까지(최대 1초) 기다린다. UI 스레드에서 불러도 된다. */
    fun detach() {
        val j = job ?: return
        job = null
        j.cancel()
        runBlocking { withTimeoutOrNull(1_000) { j.join() } }
    }

    override fun close() {
        detach()
        executor.shutdown()
    }


}
