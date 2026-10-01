package io.github.eoeo0326.adbmirror

import android.app.Application

/** 앱 전체에서 하나인 연결·저장소. 화면이 다시 만들어져도(회전) 무선 연결이 유지된다. */
class AdbMirrorApplication : Application() {
    // adb 개인키는 페어링된 기기의 shell 권한이라 백업·기기 이전에 실리지 않는 noBackupFilesDir에 둔다.
    val graph: AndroidAppGraph by lazy { AndroidAppGraph(this, noBackupFilesDir) }
}
