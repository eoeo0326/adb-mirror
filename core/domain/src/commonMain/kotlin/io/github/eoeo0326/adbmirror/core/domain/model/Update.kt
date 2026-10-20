package io.github.eoeo0326.adbmirror.core.domain.model

/** 앱 버전(MAJOR.MINOR.PATCH). 릴리즈 태그 `v0.8.0`과 `appVersion` `0.8.0`을 같은 값으로 읽는다. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

        fun parse(text: String): AppVersion? = PATTERN.matchEntire(text.trim())?.destructured?.let { (a, b, c) ->
            AppVersion(a.toInt(), b.toInt(), c.toInt())
        }
    }
}

/** 받을 수 있는 새 버전. */
data class UpdateInfo(val version: AppVersion, val notesUrl: String)

/** 이 앱이 어떻게 설치됐는지. 받을 파일과 설치 방법이 여기서 갈린다. */
enum class InstallKind {
    MacApp,
    WindowsMsi,
    WindowsPortable,
    LinuxDeb,
    LinuxRpm,
    LinuxPortable,

    /** 개발 실행(Gradle) 등 설치본이 아니라 교체할 수 없다. 확인은 된다. */
    Unsupported,
    ;

    /** 앱이 직접 교체할 수 있는지. Linux 패키지는 관리자 권한이 필요해 안내만 한다. */
    val selfInstall: Boolean get() = this == MacApp || this == WindowsMsi || this == WindowsPortable || this == LinuxPortable
}

/** 내려받아 해시까지 확인한 업데이트 파일. */
data class DownloadedUpdate(val info: UpdateInfo, val file: String)

/** 설정 화면·알림이 보여 주는 업데이트 진행 상태. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    /** [progress]는 0..1, 크기를 모르면 null. */
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateStatus
    data class Ready(val update: DownloadedUpdate) : UpdateStatus
    /** 앱이 직접 설치할 수 없어(Linux 패키지) 내려받은 파일과 설치 명령을 안내한다. */
    data class ManualInstall(val update: DownloadedUpdate, val command: String) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}
