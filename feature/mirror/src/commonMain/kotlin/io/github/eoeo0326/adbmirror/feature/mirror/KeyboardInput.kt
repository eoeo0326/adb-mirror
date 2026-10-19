package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceKey

/**
 * 키보드 입력을 받아 기기로 넘기는 보이지 않는 입력란. 컴퓨터 입력기(IME)가 한/영 전환·한글 조합을 맡고,
 * 조합이 끝나 확정된 글자만 [onIntent]로 보낸다. 조합 중인 글자는 입력란 자리에 잠깐 보인다.
 * 입력란이 비어 있을 때 누른 Enter·Backspace·방향키 같은 키는 기기 키로 보낸다.
 */
@Composable
internal fun KeyboardInput(focusRequester: FocusRequester, onIntent: (MirrorIntent) -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(TextFieldValue()) }
    /** ⌘V·Ctrl+V를 누른 직후의 변경은 붙여 넣은 글이다. */
    var pasting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (next.composition != null) {
                value = next // 조합 중: 확정될 때까지 둔다
                return@BasicTextField
            }
            value = TextFieldValue()
            if (next.text.isNotEmpty()) onIntent(MirrorIntent.Text(next.text, paste = pasting))
            pasting = false
        },
        modifier = modifier
            .widthIn(min = 1.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val shortcut = event.isMetaPressed || event.isCtrlPressed
                if (shortcut) {
                    pasting = event.key == Key.V
                    return@onPreviewKeyEvent false // 붙여넣기는 입력란이 받게 둔다
                }
                pasting = false
                if (value.text.isNotEmpty() || event.isAltPressed) return@onPreviewKeyEvent false // 조합 중이면 입력기가 처리
                val key = event.deviceKey() ?: return@onPreviewKeyEvent false
                onIntent(MirrorIntent.Key(key))
                true
            },
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(Color.Transparent),
        decorationBox = { inner ->
            if (value.text.isEmpty()) {
                inner()
            } else {
                Box(Modifier.background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) { inner() }
            }
        },
    )
}

private fun KeyEvent.deviceKey(): DeviceKey? = when (key) {
    Key.Enter, Key.NumPadEnter -> DeviceKey.Enter
    Key.Backspace -> DeviceKey.Backspace
    Key.Delete -> DeviceKey.ForwardDelete
    Key.Tab -> DeviceKey.Tab
    Key.Escape -> DeviceKey.Escape
    Key.DirectionUp -> DeviceKey.Up
    Key.DirectionDown -> DeviceKey.Down
    Key.DirectionLeft -> DeviceKey.Left
    Key.DirectionRight -> DeviceKey.Right
    Key.MoveHome -> DeviceKey.Home
    Key.MoveEnd -> DeviceKey.End
    Key.PageUp -> DeviceKey.PageUp
    Key.PageDown -> DeviceKey.PageDown
    else -> null
}
