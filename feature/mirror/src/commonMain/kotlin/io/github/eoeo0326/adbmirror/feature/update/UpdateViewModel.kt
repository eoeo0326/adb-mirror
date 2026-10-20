package io.github.eoeo0326.adbmirror.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.model.AppVersion
import io.github.eoeo0326.adbmirror.core.domain.model.DownloadedUpdate
import io.github.eoeo0326.adbmirror.core.domain.model.InstallKind
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateInfo
import io.github.eoeo0326.adbmirror.core.domain.model.UpdateStatus
import io.github.eoeo0326.adbmirror.core.domain.usecase.CheckForUpdateUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DownloadUpdateUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.InstallUpdateUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 업데이트에 쓰는 UseCase 묶음. 업데이트를 지원하는 플랫폼(Desktop)만 만든다. */
class UpdateActions(
    val current: AppVersion,
    val kind: InstallKind,
    val check: CheckForUpdateUseCase,
    val download: DownloadUpdateUseCase,
    val install: InstallUpdateUseCase,
    /** 직접 설치할 수 없는 형태(Linux 패키지)에서 보여 줄 설치 명령 */
    val manualCommand: (DownloadedUpdate) -> String,
)

data class UpdateState(
    val current: AppVersion,
    val kind: InstallKind,
    val status: UpdateStatus = UpdateStatus.Idle,
    /** 준비된 업데이트 알림을 "나중에"로 닫았는지. 설정 화면에는 계속 보인다. */
    val dismissed: Boolean = false,
) {
    /** 앱이 직접 내려받을 수 있는지(개발 실행은 못 함) */
    val canDownload: Boolean get() = kind != InstallKind.Unsupported

    /** 기기 목록 창에 띄울 알림 */
    val showBanner: Boolean get() = !dismissed && (status is UpdateStatus.Ready || status is UpdateStatus.ManualInstall)
}

sealed interface UpdateIntent {
    /** 지금 확인. 새 버전이 있으면 내려받기 버튼을 보인다. */
    data object Check : UpdateIntent
    data object Download : UpdateIntent
    /** 앱을 끝내고 설치한 뒤 다시 연다. */
    data object InstallNow : UpdateIntent
    data object Later : UpdateIntent
    data object OpenNotes : UpdateIntent
    /** 내려받은 파일이 있는 폴더를 연다(Linux 패키지). */
    data object OpenFolder : UpdateIntent
}

sealed interface UpdateEffect {
    /** 설치 프로그램을 띄웠다. 앱을 정상 종료해야 설치가 시작된다. */
    data object Quit : UpdateEffect
    data class OpenUrl(val url: String) : UpdateEffect
    data class OpenFile(val path: String) : UpdateEffect
}

/**
 * 앱 하나에 하나 두는 업데이트 상태. 자동 업데이트가 켜져 있으면 만들 때 확인하고 새 버전을 내려받는다.
 * 설치는 사용자가 고를 때만 한다(미러링 중에 앱이 꺼지지 않게).
 */
class UpdateViewModel(private val actions: UpdateActions, getSettings: GetSettingsUseCase) : ViewModel() {
    private val _state = MutableStateFlow(UpdateState(actions.current, actions.kind))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _effects = Channel<UpdateEffect>(Channel.BUFFERED)
    val effects: Flow<UpdateEffect> = _effects.receiveAsFlow()

    /** 확인·내려받기·설치는 한 번에 하나만 한다. */
    private var job: Job? = null

    init {
        viewModelScope.launch {
            if (getSettings().first().autoUpdate) launchOnce { checkThenDownload(auto = true) }
        }
    }

    fun onIntent(intent: UpdateIntent) {
        when (intent) {
            UpdateIntent.Check -> launchOnce { checkThenDownload(auto = false) }
            UpdateIntent.Download -> (_state.value.status as? UpdateStatus.Available)?.let { available -> launchOnce { download(available.info) } }
            UpdateIntent.InstallNow -> (_state.value.status as? UpdateStatus.Ready)?.let { ready -> launchOnce { install(ready.update) } }
            UpdateIntent.Later -> _state.update { it.copy(dismissed = true) }
            UpdateIntent.OpenNotes -> notesUrl()?.let { _effects.trySend(UpdateEffect.OpenUrl(it)) }
            UpdateIntent.OpenFolder -> (_state.value.status as? UpdateStatus.ManualInstall)?.let {
                _effects.trySend(UpdateEffect.OpenFile(it.update.file.substringBeforeLast('/').substringBeforeLast('\\')))
            }
        }
    }

    private fun launchOnce(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch { block() }
    }

    /** 자동이면 새 버전을 바로 내려받고, 실패해도 알리지 않는다(오프라인 등). */
    private suspend fun checkThenDownload(auto: Boolean) {
        setStatus(UpdateStatus.Checking)
        val info = try {
            actions.check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(if (auto) UpdateStatus.Idle else UpdateStatus.Failed("업데이트를 확인하지 못했습니다: ${e.message ?: e::class.simpleName}"))
            return
        }
        if (info == null) {
            setStatus(if (auto) UpdateStatus.Idle else UpdateStatus.UpToDate)
            return
        }
        setStatus(UpdateStatus.Available(info))
        if (auto && _state.value.canDownload) download(info, quiet = true)
    }

    private suspend fun download(info: UpdateInfo, quiet: Boolean = false) {
        if (!_state.value.canDownload) return
        setStatus(UpdateStatus.Downloading(info, 0f))
        var shown = -1
        val update = try {
            actions.download(info) { progress ->
                // 진행률은 1% 단위로만 바꿔 화면을 너무 자주 다시 그리지 않는다.
                val percent = progress?.let { (it * 100).toInt() } ?: -1
                if (percent != shown) {
                    shown = percent
                    setStatus(UpdateStatus.Downloading(info, progress))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(if (quiet) UpdateStatus.Available(info) else UpdateStatus.Failed("내려받지 못했습니다: ${e.message ?: e::class.simpleName}"))
            return
        }
        _state.update { it.copy(dismissed = false) }
        setStatus(
            if (actions.kind.selfInstall) UpdateStatus.Ready(update) else UpdateStatus.ManualInstall(update, actions.manualCommand(update)),
        )
    }

    private suspend fun install(update: DownloadedUpdate) {
        try {
            actions.install(update)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(UpdateStatus.Failed(e.message ?: "설치하지 못했습니다"))
            return
        }
        _effects.trySend(UpdateEffect.Quit)
    }

    private fun notesUrl(): String? = when (val s = _state.value.status) {
        is UpdateStatus.Available -> s.info.notesUrl
        is UpdateStatus.Downloading -> s.info.notesUrl
        is UpdateStatus.Ready -> s.update.info.notesUrl
        is UpdateStatus.ManualInstall -> s.update.info.notesUrl
        else -> null
    }

    private fun setStatus(status: UpdateStatus) = _state.update { it.copy(status = status) }
}
