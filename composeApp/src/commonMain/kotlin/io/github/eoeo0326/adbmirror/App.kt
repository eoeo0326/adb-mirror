package io.github.eoeo0326.adbmirror

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.github.eoeo0326.adbmirror.feature.mirror.MirrorScreen

@Composable
fun App(platform: String) {
    MaterialTheme {
        MirrorScreen(platform)
    }
}
