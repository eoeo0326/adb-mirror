package io.github.eoeo0326.adbmirror.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetSettingsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SettingsIntent {
    data class SetMaxSize(val value: Int) : SettingsIntent
    data class SetMaxFps(val value: Int) : SettingsIntent
    data object ToggleViewOnly : SettingsIntent
    data object ToggleTouchEffect : SettingsIntent
    data object ToggleShowTouches : SettingsIntent
    /** null이면 플랫폼 기본 위치 */
    data class SetOutputDir(val path: String?) : SettingsIntent
    /** null이면 자동으로 찾기 */
    data class SetAdbPath(val path: String?) : SettingsIntent
}

/** 설정 화면. State는 저장소의 설정 그대로이고, Intent는 저장소 갱신 하나로 끝난다. */
class SettingsViewModel(getSettings: GetSettingsUseCase, private val updateSettings: UpdateSettingsUseCase) : ViewModel() {
    val state: StateFlow<Settings> = getSettings().stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    fun onIntent(intent: SettingsIntent) {
        viewModelScope.launch { updateSettings { reduce(it, intent) } }
    }

    internal companion object {
        fun reduce(s: Settings, intent: SettingsIntent): Settings = when (intent) {
            is SettingsIntent.SetMaxSize -> s.copy(maxSize = intent.value)
            is SettingsIntent.SetMaxFps -> s.copy(maxFps = intent.value)
            SettingsIntent.ToggleViewOnly -> s.copy(viewOnly = !s.viewOnly)
            SettingsIntent.ToggleTouchEffect -> s.copy(touchEffect = !s.touchEffect)
            SettingsIntent.ToggleShowTouches -> s.copy(showTouches = !s.showTouches)
            is SettingsIntent.SetOutputDir -> s.copy(outputDir = intent.path)
            is SettingsIntent.SetAdbPath -> s.copy(adbPath = intent.path)
        }
    }
}
