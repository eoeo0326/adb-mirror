package io.github.eoeo0326.adbmirror

import android.app.Application

/** 앱 전체에서 하나인 연결·저장소. 화면이 다시 만들어져도(회전) 무선 연결이 유지된다. */
class AdbMirrorApplication : Application() {
    val graph: AndroidAppGraph by lazy { AndroidAppGraph(filesDir) }
}
