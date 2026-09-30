package io.github.eoeo0326.adbmirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme { Surface(Modifier.fillMaxSize()) { content() } }
}

/** 아직 미러링을 지원하지 않는 플랫폼(Android #18·#19, Web #20·#21)의 자리 표시 화면. */
@Composable
fun PlaceholderApp(platform: String) = AppTheme {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ADB Mirror", style = MaterialTheme.typography.headlineMedium)
        Text("$platform 버전은 준비 중입니다", style = MaterialTheme.typography.bodyMedium)
    }
}
