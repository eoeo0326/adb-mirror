package io.github.eoeo0326.adbmirror.core.domain.model

/** adb가 보고한 기기 하나. */
data class Device(
    val serial: String,
    val state: DeviceState,
    /** 기기 모델명. adb가 알려주지 않으면 null. */
    val model: String? = null,
)

enum class DeviceState {
    /** 연결돼 있고 USB 디버깅을 허용한 상태. 이 상태만 미러링할 수 있다. */
    Online,

    /** 기기에서 USB 디버깅 허용을 기다리는 상태. */
    Unauthorized,

    /** 연결은 보이지만 응답하지 않는 상태. */
    Offline,
}

val Device.isSelectable: Boolean get() = state == DeviceState.Online
