package io.github.eoeo0326.adbmirror.core.data.screenshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/** AWT 클립보드와 파일 시스템으로 내보낸다. 기본 저장 위치는 바탕화면(없으면 사진, 그것도 없으면 홈)이다. */
class DesktopScreenshotSink(
    private val home: File = File(System.getProperty("user.home")),
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ScreenshotSink {
    override suspend fun copyToClipboard(png: ByteArray) = withContext(Dispatchers.IO) {
        val image = ImageIO.read(png.inputStream()) ?: error("PNG를 읽지 못했습니다")
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image), null)
    }

    override suspend fun save(png: ByteArray, dir: String?, baseName: String): String = withContext(Dispatchers.IO) {
        val folder = dir?.let(::File) ?: defaultDir()
        folder.mkdirs()
        val stamp = now().format(STAMP)
        for (n in 1..999) {
            val file = File(folder, if (n == 1) "${baseName}_$stamp.png" else "${baseName}_${stamp}_$n.png")
            try {
                // CREATE_NEW: 같은 초에 두 번 저장해도 앞 파일을 덮어쓰지 않는다.
                Files.write(file.toPath(), png, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                return@withContext file.absolutePath
            } catch (_: FileAlreadyExistsException) {
            }
        }
        error("${folder.path}에 같은 시각의 파일이 너무 많습니다")
    }

    fun defaultDir(): File =
        listOf("Desktop", "Pictures").map { File(home, it) }.firstOrNull { it.isDirectory } ?: home

    private class ImageSelection(private val image: Image) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
