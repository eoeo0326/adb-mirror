package io.github.eoeo0326.adbmirror.feature.conversion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConvertRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetConversionFormatsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetVideoInfoUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 녹화 파일 변환 화면. Intent → UseCase → [ConversionResult] → [ConversionReducer] → [state]. */
class ConversionViewModel(
    private val getVideoInfo: GetVideoInfoUseCase,
    private val getConversionFormats: GetConversionFormatsUseCase,
    private val convertRecording: ConvertRecordingUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversionUiState())
    val state: StateFlow<ConversionUiState> = _state.asStateFlow()

    private val _effects = Channel<ConversionEffect>(Channel.BUFFERED)
    val effects: Flow<ConversionEffect> = _effects.receiveAsFlow()

    private var conversionJob: Job? = null

    fun onIntent(intent: ConversionIntent) {
        when (intent) {
            is ConversionIntent.Open -> open(intent.files)
            is ConversionIntent.ChangeOptions -> reduce(ConversionResult.OptionsChanged(intent.options))
            ConversionIntent.Convert -> convert()
            ConversionIntent.Cancel -> cancel()
            ConversionIntent.Close -> {
                cancel()
                reduce(ConversionResult.Closed)
                _effects.trySend(ConversionEffect.Closed)
            }
        }
    }

    private fun open(files: List<String>) {
        if (files.isEmpty() || _state.value.conversion is ConversionState.Converting) return
        reduce(ConversionResult.Loading)
        viewModelScope.launch {
            try {
                val formats = getConversionFormats()
                if (formats.isEmpty()) {
                    fail("이 플랫폼에서는 GIF·WebP 변환을 지원하지 않습니다")
                    return@launch
                }
                val info = getVideoInfo(files)
                val options = ConversionOptions(width = minOf(ConversionOptions().width, info.width))
                reduce(ConversionResult.Opened(ConversionDraft(files, info, options, formats)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("녹화 파일을 읽지 못했습니다: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun fail(message: String) {
        reduce(ConversionResult.LoadFailed(message))
        _effects.trySend(ConversionEffect.ShowMessage(message))
    }

    private fun convert() {
        val draft = _state.value.draft ?: return
        if (_state.value.conversion is ConversionState.Converting || draft.problems.isNotEmpty()) return
        reduce(ConversionResult.Progressed(0f))
        conversionJob = viewModelScope.launch {
            try {
                convertRecording(draft.files, draft.options).collect { progress ->
                    when (progress) {
                        is ConversionProgress.Running -> reduce(ConversionResult.Progressed(progress.fraction))
                        is ConversionProgress.Done -> {
                            reduce(ConversionResult.Finished(progress.file, progress.uri))
                            _effects.trySend(ConversionEffect.Done(progress.file))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reduce(ConversionResult.Failed(e.message ?: e::class.simpleName ?: "변환에 실패했습니다"))
            }
        }
    }

    /** 흐름을 취소하면 저장소가 쓰던 임시 파일을 지운다. */
    private fun cancel() {
        val job = conversionJob ?: return
        conversionJob = null
        if (job.isActive) {
            job.cancel()
            reduce(ConversionResult.Cancelled)
        }
    }

    /** 창·화면을 닫거나 앱을 끝내기 직전에 부른다. 진행 중인 변환을 취소하고 임시 파일 정리까지 기다린다. */
    suspend fun shutdown() = withContext(NonCancellable) {
        conversionJob?.cancelAndJoin()
    }

    private fun reduce(result: ConversionResult) = _state.update { ConversionReducer.reduce(it, result) }
}
