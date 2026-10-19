package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceKey
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorOptions
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.isSelectable
import io.github.eoeo0326.adbmirror.core.domain.repository.MirrorRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first

class DeviceNotSelectableException(val device: Device) :
    IllegalStateException("미러링할 수 없는 기기 상태: ${device.serial} (${device.state})")

/** 사용자가 고른 기기로 미러링을 시작한다. 옵션은 현재 설정에서 만든다. */
class StartMirroringUseCase(
    private val mirror: MirrorRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(device: Device): MirrorSession {
        if (!device.isSelectable) throw DeviceNotSelectableException(device)
        val current = settings.settings.first()
        val options = MirrorOptions(
            maxSize = current.maxSize,
            maxFps = current.maxFps,
            // 보기 전용이어도 녹화의 key frame 요청(RESET_VIDEO)에 컨트롤 소켓이 필요하므로 연다.
            control = true,
        )
        return mirror.start(device.serial, options)
    }
}

/**
 * 좌표를 현재 영상 범위 안으로 맞춰 보낸다. 창 밖으로 드래그해도 가장자리 좌표가 간다.
 * 보기 전용 설정이면 보내지 않는다. 컨트롤 소켓은 항상 열려 있으므로 여기서 막아야 한다.
 */
class SendTouchUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(session: MirrorSession, event: TouchEvent) {
        if (settings.settings.first().viewOnly) return
        val size = event.videoSize
        session.sendTouch(
            event.copy(
                x = event.x.coerceIn(0, size.width - 1),
                y = event.y.coerceIn(0, size.height - 1),
            ),
        )
    }
}

/** 키 하나를 보낸다. 보기 전용이면 보내지 않는다. */
class SendKeyUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(session: MirrorSession, key: DeviceKey) {
        if (settings.settings.first().viewOnly) return
        session.pressKey(key)
    }
}

/**
 * 글자를 보낸다. 보기 전용이면 보내지 않는다.
 * - 친 글자: 줄바꿈은 Enter 키로 보낸다. 줄마다 ASCII만 있으면 키 입력(INJECT_TEXT)으로, 아니면(한글 등) 붙여넣기로 보낸다.
 * - [paste]: 줄바꿈까지 그대로 붙여넣기로 보낸다. 여러 줄을 붙여 넣어도 Enter가 눌리지 않는다.
 */
class SendTextUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(session: MirrorSession, text: String, paste: Boolean = false) {
        if (text.isEmpty() || settings.settings.first().viewOnly) return
        if (paste) return session.pasteText(text)
        text.replace("\r\n", "\n").split('\n').forEachIndexed { i, line ->
            if (i > 0) session.pressKey(DeviceKey.Enter)
            when {
                line.isEmpty() -> Unit
                line.all { it in TYPABLE } -> line.chunked(MAX_INJECT_TEXT).forEach { session.injectText(it) }
                else -> session.pasteText(line)
            }
        }
    }

    companion object {
        /** 기기 키맵에 있다고 볼 수 있는 글자. 이 밖의 글자는 서버가 키 입력으로 바꾸지 못한다. */
        private val TYPABLE = ' '..'~'

        /** scrcpy INJECT_TEXT 한 번의 최대 길이(바이트). [TYPABLE]은 1바이트라 글자 수와 같다. */
        const val MAX_INJECT_TEXT = 300
    }
}

class StopMirroringUseCase {
    suspend operator fun invoke(session: MirrorSession) = session.stop()
}
