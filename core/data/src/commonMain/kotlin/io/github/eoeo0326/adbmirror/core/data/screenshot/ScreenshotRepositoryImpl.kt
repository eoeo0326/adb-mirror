package io.github.eoeo0326.adbmirror.core.data.screenshot

import io.github.eoeo0326.adbmirror.core.adb.AdbTransport
import io.github.eoeo0326.adbmirror.core.domain.model.Screenshot
import io.github.eoeo0326.adbmirror.core.domain.repository.ScreenshotRepository

/** 스크린샷을 클립보드·파일로 내보내는 플랫폼 쪽 경계. */
interface ScreenshotSink {
    suspend fun copyToClipboard(png: ByteArray)

    /** [dir](null이면 플랫폼 기본 위치)에 `[baseName]_<시각>.png`로 저장하고 경로를 돌려준다. 같은 이름이 있으면 덮어쓰지 않는다. */
    suspend fun save(png: ByteArray, dir: String?, baseName: String): String
}

/** `screencap -p`로 기기 원본 해상도 PNG를 받는다. 미러링 영상(축소·압축)과 달리 화질 손실이 없다. */
class ScreenshotRepositoryImpl(
    private val transport: AdbTransport,
    private val sink: ScreenshotSink,
) : ScreenshotRepository {
    override suspend fun capture(serial: String): Screenshot {
        val png = transport.execOut(serial, listOf("screencap", "-p"))
        require(png.isPng()) { "스크린샷을 받지 못했습니다(기기 화면이 잠겨 있거나 보호된 화면일 수 있습니다)" }
        return Screenshot(serial, png)
    }

    override suspend fun copyToClipboard(screenshot: Screenshot) = sink.copyToClipboard(screenshot.png)

    override suspend fun save(screenshot: Screenshot, outputDir: String?): String =
        sink.save(screenshot.png, outputDir, baseName(screenshot.serial))

    internal companion object {
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun ByteArray.isPng() = size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { this[it] == PNG_SIGNATURE[it] }

        /** 무선 기기 serial(`192.168.0.5:5555`)처럼 파일 이름에 못 쓰는 문자는 `_`로 바꾼다. */
        fun baseName(serial: String) = "adb-mirror_" + serial.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
