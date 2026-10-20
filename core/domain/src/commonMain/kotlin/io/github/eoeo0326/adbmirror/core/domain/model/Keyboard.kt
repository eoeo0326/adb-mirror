package io.github.eoeo0326.adbmirror.core.domain.model

/** 기기로 보내는 글자가 아닌 키. 글자는 [MirrorSession.injectText]·[MirrorSession.pasteText]로 보낸다. */
enum class DeviceKey {
    Enter, Backspace, ForwardDelete, Tab, Escape,
    Up, Down, Left, Right,
    Home, End, PageUp, PageDown,
}
