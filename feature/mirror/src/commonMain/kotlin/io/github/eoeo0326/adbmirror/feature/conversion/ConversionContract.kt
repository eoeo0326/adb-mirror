package io.github.eoeo0326.adbmirror.feature.conversion

import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo

/**
 * 녹화 파일 → GIF·WebP 변환 화면의 MVI 계약. 미러링과 수명을 따로 가져, Desktop은 별도 창으로 띄우고
 * (미러링 창을 닫아도 변환은 이어진다) Android·Web은 미러링 화면 위에 덮어 띄운다.
 */
data class ConversionUiState(
    /** 파일 정보를 읽는 중 */
    val loading: Boolean = false,
    /** 열려 있으면 그 대상과 옵션 */
    val draft: ConversionDraft? = null,
    val conversion: ConversionState = ConversionState.Idle,
    /** 파일을 읽지 못했거나 이 플랫폼이 변환을 지원하지 않을 때의 사유 */
    val loadError: String? = null,
)

/** 변환 화면에서 고르는 중인 내용. [files]가 여럿이면 회전으로 나뉜 part를 이어서 변환한다. */
data class ConversionDraft(
    val files: List<String>,
    val info: VideoInfo,
    val options: ConversionOptions,
    val formats: Set<AnimatedFormat>,
) {
    val estimatedBytes: Long get() = options.estimatedBytes(info)
    val isLarge: Boolean get() = estimatedBytes > ConversionOptions.LARGE_OUTPUT_BYTES
    val problems: List<String> get() = options.problems() + listOfNotNull("이 플랫폼에서는 만들 수 없는 형식입니다".takeIf { options.format !in formats })
}

sealed interface ConversionState {
    data object Idle : ConversionState
    data class Converting(val fraction: Float) : ConversionState
    /** [file]은 보여줄 저장 위치, [uri]는 다른 앱으로 열 주소(없으면 [file]을 연다). */
    data class Done(val file: String, val uri: String? = null) : ConversionState
    data class Failed(val message: String) : ConversionState
}

sealed interface ConversionIntent {
    /** 녹화 파일로 화면을 연다. 회전으로 나뉜 녹화면 part를 모두 넘긴다. */
    data class Open(val files: List<String>) : ConversionIntent
    data class ChangeOptions(val options: ConversionOptions) : ConversionIntent
    /** 지금 옵션으로 변환한다. */
    data object Convert : ConversionIntent
    data object Cancel : ConversionIntent
    /** 변환 중이면 취소하고 닫는다. */
    data object Close : ConversionIntent
}

sealed interface ConversionResult {
    data object Loading : ConversionResult
    data class Opened(val draft: ConversionDraft) : ConversionResult
    data class LoadFailed(val message: String) : ConversionResult
    data class OptionsChanged(val options: ConversionOptions) : ConversionResult
    data object Closed : ConversionResult
    data class Progressed(val fraction: Float) : ConversionResult
    data class Finished(val file: String, val uri: String?) : ConversionResult
    data class Failed(val message: String) : ConversionResult
    data object Cancelled : ConversionResult
}

sealed interface ConversionEffect {
    /** 화면을 열지 못한 사유(미러링 화면 위에 띄울 때는 스낵바로 보인다). */
    data class ShowMessage(val message: String) : ConversionEffect
    data class Done(val file: String) : ConversionEffect
    /** 닫기를 눌렀다. 별도 창이면 창을 닫는다. */
    data object Closed : ConversionEffect
}

object ConversionReducer {
    fun reduce(state: ConversionUiState, result: ConversionResult): ConversionUiState = when (result) {
        // 변환하는 동안에는 다른 파일로 바꾸거나 옵션을 바꾸지 않는다(진행 중인 변환과 화면이 어긋나지 않게).
        ConversionResult.Loading -> if (state.conversion is ConversionState.Converting) state else state.copy(loading = true, loadError = null)
        is ConversionResult.Opened ->
            if (state.conversion is ConversionState.Converting) state else ConversionUiState(draft = result.draft)
        is ConversionResult.LoadFailed -> state.copy(loading = false, loadError = result.message)
        // 옵션을 바꾸면 지난 결과(저장 경로·실패 사유)는 지운다.
        is ConversionResult.OptionsChanged ->
            if (state.conversion is ConversionState.Converting) {
                state
            } else {
                state.copy(draft = state.draft?.copy(options = result.options), conversion = ConversionState.Idle)
            }
        ConversionResult.Closed -> ConversionUiState()
        is ConversionResult.Progressed -> state.copy(conversion = ConversionState.Converting(result.fraction.coerceIn(0f, 1f)))
        is ConversionResult.Finished -> state.copy(conversion = ConversionState.Done(result.file, result.uri))
        is ConversionResult.Failed -> state.copy(conversion = ConversionState.Failed(result.message))
        ConversionResult.Cancelled -> state.copy(conversion = ConversionState.Idle)
    }
}
