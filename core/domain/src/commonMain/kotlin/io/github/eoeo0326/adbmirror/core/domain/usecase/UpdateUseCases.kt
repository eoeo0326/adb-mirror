package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.AppVersion
import io.github.eoeo0326.adbmirror.core.domain.model.DownloadedUpdate
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateInfo
import io.github.eoeo0326.adbmirror.core.domain.repository.UpdateRepository

/** 지금 버전보다 새 릴리즈가 있으면 돌려준다. 같거나 낮으면 null. */
class CheckForUpdateUseCase(private val updates: UpdateRepository, private val current: AppVersion) {
    suspend operator fun invoke(): UpdateInfo? = updates.latest().takeIf { it.version > current }
}

class DownloadUpdateUseCase(private val updates: UpdateRepository) {
    suspend operator fun invoke(info: UpdateInfo, onProgress: (Float?) -> Unit = {}): DownloadedUpdate = updates.download(info, onProgress)
}

/** 설치 프로그램을 띄운다. 돌아오면 앱을 종료해야 교체가 시작된다. */
class InstallUpdateUseCase(private val updates: UpdateRepository) {
    suspend operator fun invoke(update: DownloadedUpdate) {
        check(updates.installKind.selfInstall) { "이 설치 형태는 앱에서 직접 설치할 수 없습니다" }
        updates.install(update)
    }
}
