package io.github.eoeo0326.adbmirror

import io.github.eoeo0326.adbmirror.feature.conversion.FileOpener
import java.awt.Desktop
import java.io.File
import kotlin.concurrent.thread

/**
 * Desktop: [Desktop]으로 열고, 지원하지 않는 환경이면 OS 명령(macOS `open`, Windows `explorer`, Linux `xdg-open`)을 쓴다.
 * 여는 동안 UI가 멈추지 않게 따로 스레드에서 부르고, 실패는 표준 오류로만 남긴다.
 */
object DesktopFileOpener : FileOpener {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    override fun canOpen(file: String, uri: String?) = File(file).isFile

    override fun open(file: String, uri: String?) = background {
        val f = File(file)
        if (supports(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(f)
        } else {
            exec(
                when {
                    isMac -> listOf("open", f.path)
                    isWindows -> listOf("cmd", "/c", "start", "", f.path)
                    else -> listOf("xdg-open", f.path)
                },
            )
        }
    }

    override fun canReveal(file: String) = File(file).isFile

    /** 폴더를 열고 그 파일을 선택한다. Linux는 파일 관리자마다 달라 폴더만 연다. */
    override fun reveal(file: String) = background {
        val f = File(file).absoluteFile
        when {
            supports(Desktop.Action.BROWSE_FILE_DIR) -> Desktop.getDesktop().browseFileDirectory(f)
            isMac -> exec(listOf("open", "-R", f.path))
            isWindows -> exec(listOf("explorer.exe", "/select,${f.path}"))
            else -> exec(listOf("xdg-open", f.parentFile.path))
        }
    }

    private fun supports(action: Desktop.Action) = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)

    private fun exec(command: List<String>) {
        ProcessBuilder(command).redirectErrorStream(true).start()
    }

    private fun background(block: () -> Unit) {
        thread(name = "open-file", isDaemon = true) {
            try {
                block()
            } catch (e: Exception) {
                System.err.println("파일을 열지 못했습니다: ${e.message}")
            }
        }
    }
}
