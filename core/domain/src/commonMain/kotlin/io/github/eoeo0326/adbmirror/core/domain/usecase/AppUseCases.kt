package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.InstallResult
import io.github.eoeo0326.adbmirror.core.domain.repository.AppRepository

/** 기기에 APK를 설치한다(이미 있으면 덮어쓴다). `.apk` 파일만 받는다. */
class InstallApkUseCase(private val apps: AppRepository) {
    suspend operator fun invoke(serial: String, apkPath: String): InstallResult {
        require(isApk(apkPath)) { "APK 파일만 설치할 수 있습니다" }
        return apps.install(serial, apkPath)
    }

    companion object {
        fun isApk(path: String) = path.endsWith(".apk", ignoreCase = true)
    }
}
