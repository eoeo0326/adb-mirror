package io.github.eoeo0326.adbmirror.core.data.settings

import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 앱을 끄면 사라지는 설정. 파일 저장소(#12)가 생기기 전까지 쓴다. */
class InMemorySettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = state
    override suspend fun update(transform: (Settings) -> Settings) = state.update(transform)
}
