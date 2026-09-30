package io.github.eoeo0326.adbmirror.feature.mirror.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize

/**
 * 세션의 영상 패킷을 디코딩해 그리고, 포인터 입력을 영상 좌표 터치로 바꿔 [onTouch]로 넘긴다.
 * 디코딩은 플랫폼마다 다르다(Desktop FFmpeg, Android MediaCodec, Web WebCodecs).
 */
@Composable
expect fun VideoSurface(
    session: MirrorSession,
    videoSize: VideoSize?,
    onTouch: (TouchAction, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
)
