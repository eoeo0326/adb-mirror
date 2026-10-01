package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.adb.AdbTransport
import io.github.eoeo0326.adbmirror.core.adb.DeviceStream
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.adb.SocketNotReadyException
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** 기기에 올릴 scrcpy-server jar. [version]은 서버 실행 인자로 넘기며 jar와 정확히 같아야 한다. */
class ServerJar(val version: String, val bytes: ByteArray)

fun interface ServerJarSource {
    suspend fun load(): ServerJar
}

/** 실행 중인 scrcpy 서버와 그 소켓들. */
class ScrcpyConnection(
    val video: DeviceStream,
    val control: DeviceStream?,
    val process: RemoteProcess,
)

/**
 * scrcpy 서버를 영상 전용(오디오 없음)으로 띄우고 소켓을 연다.
 * forward 방식이라 서버가 listen하기 전에도 연결이 성립하므로, 첫 소켓에서 dummy byte를 받을 때까지 다시 연결한다.
 */
class ScrcpyServerLauncher(
    private val transport: AdbTransport,
    private val jarSource: ServerJarSource,
    private val random: Random = Random.Default,
    private val retryDelayMs: Long = 100,
    private val maxAttempts: Int = 50,
    private val log: (String) -> Unit = {},
    /** 정상 종료하지 못한 서버 기록. 있으면 새로 띄우기 전에 남은 서버를 정리한다. */
    private val launched: LaunchedServers? = null,
) {
    suspend fun launch(serial: String, options: MirrorOptions): ScrcpyConnection {
        killLeftovers(serial)
        val jar = jarSource.load()
        transport.push(serial, jar.bytes, DEVICE_PATH)
        val scid = random.nextInt(0, Int.MAX_VALUE).toString(16).padStart(8, '0')
        // 서버를 띄우기 전에 기록해, 띄운 직후 앱이 죽어도 다음 실행 때 정리할 수 있게 한다.
        launched?.add(serial, scid)
        val process = try {
            transport.startProcess(serial, serverCommand(jar.version, scid, options), log)
        } catch (e: Throwable) {
            withContext(NonCancellable) { launched?.remove(scid) }
            throw e
        }
        val tracked = object : RemoteProcess {
            override suspend fun awaitExit() = process.awaitExit()
            override suspend fun stop() {
                withContext(NonCancellable) {
                    process.stop()
                    launched?.remove(scid)
                }
            }
        }
        var video: DeviceStream? = null
        try {
            val socketName = "scrcpy_$scid"
            video = connectVideo(serial, socketName)
            val control = if (options.control) transport.openLocalAbstract(serial, socketName) else null
            return ScrcpyConnection(video, control, tracked)
        } catch (e: Throwable) {
            // 취소로 빠져나가는 경우에도 서버·소켓·forward를 남기지 않는다.
            withContext(NonCancellable) {
                video?.close()
                tracked.stop()
            }
            throw e
        }
    }

    /**
     * 앞선 실행이 이 기기에 남긴 서버를 끝낸다. scid로만 찾으므로 다른 클라이언트가 띄운 서버는 건드리지 않는다.
     * 정리에 실패해도 새 서버는 그대로 띄운다(scid가 달라 겹치지 않음).
     */
    private suspend fun killLeftovers(serial: String) {
        val leftovers = launched?.takeLeftovers(serial).orEmpty()
        for (scid in leftovers) {
            // pkill은 찾지 못하면 1로 끝나 실패로 보이지만 정상이다. 패턴 첫 글자를 [x]로 감싸, 이 명령을 실행하는
            // 셸 자신의 명령줄("pkill -f scid=[x]…")은 맞지 않게 한다(자기 셸을 죽이지 않음).
            try {
                transport.shell(serial, listOf("pkill", "-f", "'scid=[${scid.first()}]${scid.drop(1)}'"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            log("남아 있던 scrcpy 서버 정리 시도: scid=$scid")
        }
    }

    private suspend fun connectVideo(serial: String, socketName: String): DeviceStream {
        repeat(maxAttempts) {
            // 서버가 아직 소켓을 열지 않았다: 전송에 따라 연결 거절(Kadb) 또는 연결 후 바로 끝남(adb forward).
            val stream = try {
                transport.openLocalAbstract(serial, socketName)
            } catch (_: SocketNotReadyException) {
                delay(retryDelayMs)
                return@repeat
            }
            try {
                stream.source.readFully(1) // dummy byte
                return stream
            } catch (_: EndOfStreamException) {
                stream.close()
                delay(retryDelayMs)
            } catch (e: Throwable) {
                withContext(NonCancellable) { stream.close() }
                throw e
            }
        }
        throw ScrcpyException("scrcpy 서버에 연결하지 못했습니다 (${maxAttempts * retryDelayMs}ms 초과)")
    }

    internal fun serverCommand(version: String, scid: String, options: MirrorOptions): List<String> = listOf(
        "CLASSPATH=$DEVICE_PATH", "app_process", "/", "com.genymobile.scrcpy.Server", version,
        "scid=$scid",
        "tunnel_forward=true",
        "video=true",
        "audio=false",
        "control=${options.control}",
        "video_codec=h264",
        "max_size=${options.maxSize}",
        "max_fps=${options.maxFps}",
        "cleanup=true",
        // 기기 클립보드 변경 알림은 쓰지 않는다(컨트롤 소켓으로 들어오는 메시지 최소화).
        "clipboard_autosync=false",
        "log_level=info",
    )

    companion object {
        const val DEVICE_PATH = "/data/local/tmp/scrcpy-server.jar"
    }
}

class ScrcpyException(message: String) : Exception(message)
