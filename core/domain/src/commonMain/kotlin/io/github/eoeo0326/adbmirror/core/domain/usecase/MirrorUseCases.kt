package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.Device
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

class StopMirroringUseCase {
    suspend operator fun invoke(session: MirrorSession) = session.stop()
}
