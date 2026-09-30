package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.adb.AdbTransport
import io.github.eoeo0326.adbmirror.core.adb.DeviceStream
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.adb.RemoteProcess
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import kotlinx.coroutines.delay
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
) {
    suspend fun launch(serial: String, options: MirrorOptions): ScrcpyConnection {
        val jar = jarSource.load()
        transport.push(serial, jar.bytes, DEVICE_PATH)
        val scid = random.nextInt(0, Int.MAX_VALUE).toString(16).padStart(8, '0')
        val process = transport.startProcess(serial, serverCommand(jar.version, scid, options), log)
        try {
            val socketName = "scrcpy_$scid"
            val video = connectVideo(serial, socketName)
            val control = if (options.control) transport.openLocalAbstract(serial, socketName) else null
            return ScrcpyConnection(video, control, process)
        } catch (e: Throwable) {
            process.stop()
            throw e
        }
    }

    private suspend fun connectVideo(serial: String, socketName: String): DeviceStream {
        repeat(maxAttempts) {
            val stream = transport.openLocalAbstract(serial, socketName)
            try {
                stream.source.readFully(1) // dummy byte
                return stream
            } catch (_: EndOfStreamException) {
                stream.close()
                delay(retryDelayMs)
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
