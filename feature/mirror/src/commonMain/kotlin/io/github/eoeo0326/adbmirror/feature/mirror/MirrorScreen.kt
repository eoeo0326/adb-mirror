package io.github.eoeo0326.adbmirror.feature.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** 미러링 화면. 지금은 모듈 연결을 확인하는 자리 표시 화면이다. */
@Composable
fun MirrorScreen(platform: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ADB Mirror", style = MaterialTheme.typography.headlineMedium)
        Text("KMP 뼈대 · $platform", style = MaterialTheme.typography.bodyMedium)
    }
}
