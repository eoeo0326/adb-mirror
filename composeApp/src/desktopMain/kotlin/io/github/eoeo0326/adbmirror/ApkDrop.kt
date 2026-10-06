package io.github.eoeo0326.adbmirror

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * 파일을 끌어 놓을 수 있는 영역. 파일이 아닌 것(글자 등)은 받지 않는다.
 * [onHover]는 파일이 영역 위에 있는 동안 true, [onDrop]은 놓은 파일 경로들.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Unit): Modifier {
    val target = remember(onHover, onDrop) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = onHover(true)
            override fun onExited(event: DragAndDropEvent) = onHover(false)
            override fun onEnded(event: DragAndDropEvent) = onHover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                onHover(false)
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                }.getOrNull().orEmpty()
                if (files.isEmpty()) return false
                onDrop(files.map { it.absolutePath })
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    )
}

/** 파일을 끌고 있는 동안 미러링 창을 덮는 안내. */
@Composable
fun ApkDropOverlay(deviceName: String) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Text(
            "놓으면 ${deviceName}에 APK를 설치합니다",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}
