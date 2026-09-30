package io.github.eoeo0326.adbmirror.feature.mirror.video

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/** 이 플랫폼의 영상 디코딩은 아직 없다(Android #19, Web #21). */
@Composable
actual fun VideoSurface(
    session: MirrorSession,
    videoSize: VideoSize?,
    onTouch: (TouchAction, Int, Int) -> Unit,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) { Text("이 플랫폼의 영상 표시는 준비 중입니다") }
}
