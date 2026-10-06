package io.github.eoeo0326.adbmirror.core.domain.model

/** APK 설치 결과. 실패하면 기기가 알려 준 사유(`INSTALL_FAILED_…`)를 담는다. */
sealed interface InstallResult {
    data object Success : InstallResult
    data class Failure(val reason: String) : InstallResult
}
