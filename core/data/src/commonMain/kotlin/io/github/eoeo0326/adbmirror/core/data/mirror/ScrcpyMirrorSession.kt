package io.github.eoeo0326.adbmirror.core.data.mirror

import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ControlMessageSerializer
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ScrcpyConnection
import io.github.eoeo0326.adbmirror.core.data.scrcpy.VideoStreamParser
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * scrcpy 연결 하나를 [MirrorSession]으로 감싼다. 영상 소켓을 [scope]에서 읽어
 * 세션 정보는 [events]로, 패킷은 [packets]로 흘린다.
 *
 * - [events]는 최근 이벤트를 다시 보내므로 늦게 구독해도 현재 이름·해상도를 받는다.
 * - [packets]는 첫 구독자가 붙을 때까지 읽기를 미뤄 첫 config·key frame을 놓치지 않는다. 그 뒤 구독자가 모두 떠나면
 *   패킷은 버려지고, 구독자가 있으면 버퍼가 찰 때 읽기를 늦춘다(프레임을 임의로 버리지 않음).
 *   나중에 붙는 구독자(녹화)는 [requestKeyFrame]으로 config·key frame을 다시 받는다.
 */
class ScrcpyMirrorSession(
    override val serial: String,
    private val connection: ScrcpyConnection,
    scope: CoroutineScope,
) : MirrorSession {
    private val _events = MutableSharedFlow<SessionEvent>(replay = EVENT_REPLAY)
    private val _packets = MutableSharedFlow<EncodedPacket>(extraBufferCapacity = PACKET_BUFFER)
    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()
    override val packets: SharedFlow<EncodedPacket> = _packets.asSharedFlow()

    private val controlLock = Mutex()

    /** 서버가 화면 캡처를 시작했는지(첫 세션 패킷). 그 전에 RESET_VIDEO를 보내면 서버 컨트롤 스레드가 죽는다. */
    private val captureStarted = CompletableDeferred<Unit>()
    private val endLock = Mutex()
    private var ended = false

    private val reader: Job = scope.launch(start = CoroutineStart.LAZY) {
        val error = try {
            readUntilEnd()
        } catch (e: CancellationException) {
            throw e
        } catch (_: EndOfStreamException) {
            "영상 스트림이 끊겼습니다"
        } catch (e: Exception) {
            e.message ?: e::class.simpleName
        }
        end(error)
    }

    // 서버가 스스로 끝나면(USB 분리 등) 세션도 끝낸다.
    private val exitWatcher: Job = scope.launch(start = CoroutineStart.LAZY) {
        val code = connection.process.awaitExit()
        end("scrcpy 서버가 종료됐습니다 (exit $code)")
    }

    init {
        // 두 코루틴이 프로퍼티 초기화가 끝난 뒤에만 end()를 부르도록 여기서 시작한다.
        reader.start()
        exitWatcher.start()
    }

    /** 스트림이 끝나거나 오류가 날 때까지 읽는다. 정상 반환하지 않는다. */
    private suspend fun readUntilEnd(): Nothing {
        val parser = VideoStreamParser(connection.video.source)
        val header = parser.readHeader()
        if (header.codecId != VideoStreamParser.CODEC_H264) error("지원하지 않는 코덱: 0x${header.codecId.toString(16)}")
        _events.emit(SessionEvent.DeviceName(header.deviceName))
        var firstPacketDelivered = false
        var size: VideoSize? = null
        while (true) {
            when (val item = parser.readItem()) {
                is VideoStreamParser.Item.Session -> {
                    captureStarted.complete(Unit)
                    size = item.size
                    _events.emit(SessionEvent.VideoSizeChanged(item.size))
                }
                is VideoStreamParser.Item.Packet -> {
                    // 녹화는 config마다 크기를 보고 파일을 나누므로, 같은 흐름 안에서 크기를 함께 싣는다.
                    val packet = item.packet.let { p ->
                        if (p.kind == EncodedPacket.Kind.Config) EncodedPacket(p.kind, p.ptsUs, p.data, size) else p
                    }
                    if (!firstPacketDelivered) {
                        // 구독자가 붙기 전에 첫 config를 흘려 버리면 디코더가 다음 key frame까지 그리지 못한다.
                        // 첫 패킷만 구독자를 기다린다(세션 이벤트는 계속 흐르므로 requestKeyFrame이 막히지 않는다).
                        _packets.subscriptionCount.first { it > 0 }
                        firstPacketDelivered = true
                    }
                    _packets.emit(packet)
                }
            }
        }
    }

    override suspend fun sendTouch(event: TouchEvent) = sendControl(ControlMessageSerializer.touch(event))

    /** 캡처가 시작되기 전이면 시작될 때까지 기다렸다가 보낸다. */
    override suspend fun requestKeyFrame() {
        captureStarted.await()
        sendControl(ControlMessageSerializer.resetVideo())
    }

    private suspend fun sendControl(bytes: ByteArray) {
        val control = connection.control ?: return
        controlLock.withLock {
            if (!ended) runCatching { control.sink.write(bytes) }
        }
    }

    override suspend fun stop() = end(null)

    /** 한 번만 정리하고 [SessionEvent.Ended]를 보낸다. 사용자가 끊었으면 error는 null. */
    private suspend fun end(error: String?): Unit = withContext(NonCancellable) {
        endLock.withLock {
            if (ended) return@withLock
            ended = true
            captureStarted.complete(Unit) // requestKeyFrame 대기를 풀어 준다(보내지는 않음)
            reader.cancel()
            exitWatcher.cancel()
            connection.video.close()
            connection.control?.close()
            connection.process.stop()
            _events.emit(SessionEvent.Ended(error))
        }
    }

    private companion object {
        const val EVENT_REPLAY = 4
        const val PACKET_BUFFER = 64
    }
}
