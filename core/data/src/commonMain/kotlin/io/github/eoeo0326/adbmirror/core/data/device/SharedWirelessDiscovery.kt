package io.github.eoeo0326.adbmirror.core.data.device

import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDiscoveryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

/** 여러 곳(기기 목록, 알림 페어링)이 구독해도 찾기는 하나만 돌린다. 마지막 구독이 끝나고 잠시 뒤 멈춘다. */
class SharedWirelessDiscovery(source: WirelessDiscoveryRepository, scope: CoroutineScope) : WirelessDiscoveryRepository {
    private val shared = source.services().shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 1)

    override fun services(): Flow<List<WirelessService>> = shared
}
