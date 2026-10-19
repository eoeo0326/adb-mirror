package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
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
 * 조합 중이 아닐 때 누른 Enter·Backspace·방향키 같은 키는 기기 키로 보낸다.
 *
 * 입력기는 이미 확정한 앞 글자를 지우고 다시 확정하기도 한다(macOS). 그래서 보낸 글자를 입력란에 남겨 두고,
 * 확정된 글이 바뀐 만큼만(지워진 만큼 Backspace, 늘어난 만큼 글자) 보낸다.
 */
@Composable
internal fun KeyboardInput(
    focusRequester: FocusRequester,
    onIntent: (MirrorIntent) -> Unit,
    modifier: Modifier = Modifier,
    /** false면 포커스가 가도 화면 키보드를 띄우지 않는다. 입력기 연결(한글 조합)은 그대로다. */
    showSoftKeyboard: Boolean = true,
) {
    var value by remember { mutableStateOf(TextFieldValue()) }
    /** 입력란의 확정된 글 중 기기로 이미 보낸 것. */
    var sent by remember { mutableStateOf("") }
    /** ⌘V·Ctrl+V를 누른 직후의 변경은 붙여 넣은 글이다. */
    var pasting by remember { mutableStateOf(false) }
    fun clear() {
        value = TextFieldValue()
        sent = ""
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                value = next
                val committed = next.committedText()
                val change = TypedChange.between(sent, committed)
                sent = committed
                repeat(change.deleted) { onIntent(MirrorIntent.Key(DeviceKey.Backspace)) }
                if (change.added.isEmpty()) return@BasicTextField
                onIntent(MirrorIntent.Text(change.added, paste = pasting))
                // 붙여 넣은 글은 입력기가 다시 고치지 않으므로 바로 비워 입력란이 커지지 않게 한다.
                if (pasting) clear()
                pasting = false
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.isMetaPressed || event.isCtrlPressed) {
                        pasting = event.key == Key.V
                        return@onPreviewKeyEvent false // 붙여넣기는 입력란이 받게 둔다
                    }
                    pasting = false
                    if (value.composition != null || event.isAltPressed) return@onPreviewKeyEvent false // 조합 중이면 입력기가 처리
                    val key = event.deviceKey() ?: return@onPreviewKeyEvent false
                    // 기기 쪽 글이 키로 바뀌므로 입력란과 보낸 글의 짝을 여기서 끊는다.
                    clear()
                    onIntent(MirrorIntent.Key(key))
                    true
                },
            keyboardOptions = KeyboardOptions(showKeyboardOnFocus = showSoftKeyboard),
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
        )
        val composing = value.composition?.let { value.text.substring(it.start, it.end) }
        if (!composing.isNullOrEmpty()) {
            Text(
                composing,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 조합 중인 부분을 뺀 확정된 글. */
private fun TextFieldValue.committedText(): String = composition?.let { text.removeRange(it.start, it.end) } ?: text

/** 보낸 글([sent])에서 확정된 글로 가려면 끝에서 [deleted]글자를 지우고 [added]를 붙이면 된다. */
internal data class TypedChange(val deleted: Int, val added: String) {
    companion object {
        fun between(sent: String, committed: String): TypedChange {
            val common = sent.commonPrefixWith(committed).length
            return TypedChange(sent.length - common, committed.substring(common))
        }
    }
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
