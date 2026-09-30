package io.github.eoeo0326.adbmirror

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** 폴더 하나를 고른다. macOS는 기본 파일 창(폴더 모드), 그 밖에는 Swing 선택 창. 취소하면 null. */
internal fun pickFolder(title: String): String? {
    if (isMac) {
        val old = System.getProperty("apple.awt.fileDialogForDirectories")
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            return macDialog(title)
        } finally {
            if (old == null) System.clearProperty("apple.awt.fileDialogForDirectories") else System.setProperty("apple.awt.fileDialogForDirectories", old)
        }
    }
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

/** 파일 하나를 고른다. 취소하면 null. */
internal fun pickFile(title: String): String? {
    if (isMac) return macDialog(title)
    val chooser = JFileChooser().apply { dialogTitle = title }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

private fun macDialog(title: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).absolutePath
}
