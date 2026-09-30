package io.github.eoeo0326.adbmirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import io.github.eoeo0326.adbmirror.feature.mirror.Connection
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorIntent
import io.github.eoeo0326.adbmirror.feature.mirror.RecordingState

internal val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")

/** macOS는 ⌘, Windows·Linux는 Ctrl. */
internal fun shortcut(key: Key) = KeyShortcut(key, meta = isMac, ctrl = !isMac)

/**
 * 미러링 창 메뉴 막대. macOS에서는 화면 위 메뉴 막대에 붙는다(`apple.laf.useScreenMenuBar`).
 * macOS의 종료(⌘Q)는 앱 메뉴가 맡으므로 파일 메뉴에는 Windows·Linux에서만 둔다.
 */
@Composable
fun FrameWindowScope.MirrorMenuBar(holder: MirrorWindowHolder, onCloseWindow: () -> Unit, onOpenSettings: () -> Unit, onQuit: () -> Unit) {
    val state by holder.viewModel.state.collectAsState()
    val session by holder.viewModel.session.collectAsState()
    val send = holder.viewModel::onIntent
    MenuBar {
        Menu("파일") {
            Item("스크린샷 복사", shortcut = shortcut(Key.C), enabled = !state.capturingScreenshot) { send(MirrorIntent.CopyScreenshot) }
            Item("스크린샷 저장", shortcut = shortcut(Key.S), enabled = !state.capturingScreenshot) { send(MirrorIntent.SaveScreenshot) }
            val rec = state.recording
            if (rec is RecordingState.Idle) {
                Item("녹화 시작", shortcut = shortcut(Key.R), enabled = state.connection is Connection.Mirroring) { send(MirrorIntent.StartRecording) }
            } else {
                Item("녹화 정지", shortcut = shortcut(Key.R), enabled = rec is RecordingState.Recording) { send(MirrorIntent.StopRecording) }
            }
            Separator()
            Item("창 닫기", shortcut = shortcut(Key.W), onClick = onCloseWindow)
            if (!isMac) {
                Separator()
                Item("설정…", shortcut = shortcut(Key.Comma), onClick = onOpenSettings)
                Item("종료", shortcut = shortcut(Key.Q), onClick = onQuit)
            }
        }
        Menu("보기") {
            CheckboxItem("보기 전용", checked = state.settings.viewOnly) { send(MirrorIntent.ToggleViewOnly) }
            CheckboxItem("클릭 이펙트", checked = state.settings.touchEffect) { send(MirrorIntent.ToggleTouchEffect) }
            CheckboxItem("기기에 터치 표시", checked = state.settings.showTouches) { send(MirrorIntent.ToggleShowTouches) }
        }
        Menu("기기") {
            Item("연결 끊기", enabled = session != null) { send(MirrorIntent.Disconnect) }
            Item("다시 연결", enabled = state.canConnect) { send(MirrorIntent.Connect) }
        }
    }
}

/** 기기 목록 창 메뉴 막대. macOS는 앱 메뉴에 설정(⌘,)·종료가 있어 따로 두지 않는다. */
@Composable
fun FrameWindowScope.DeviceListMenuBar(onOpenSettings: () -> Unit, onQuit: () -> Unit) {
    if (isMac) return
    MenuBar {
        Menu("파일") {
            Item("설정…", shortcut = shortcut(Key.Comma), onClick = onOpenSettings)
            Item("종료", shortcut = shortcut(Key.Q), onClick = onQuit)
        }
    }
}
