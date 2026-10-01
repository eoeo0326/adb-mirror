package io.github.eoeo0326.adbmirror.core.data.device

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDiscoveryRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Android NSD로 `_adb-tls-pairing._tcp`·`_adb-tls-connect._tcp`를 찾는다(기기의 무선 디버깅 화면이 알림).
 * 콜백은 시스템 스레드에서 오므로 이벤트를 채널에 넣고 한 코루틴에서 처리한다. 주소 확인(resolve)은 한 번에 하나만 한다.
 */
class NsdWirelessDiscovery(private val context: Context) : WirelessDiscoveryRepository {
    private sealed interface Event {
        data class Found(val kind: WirelessService.Kind, val info: NsdServiceInfo) : Event
        data class Lost(val kind: WirelessService.Kind, val name: String) : Event
    }

    override fun services(): Flow<List<WirelessService>> = channelFlow {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return@channelFlow
        val events = Channel<Event>(Channel.UNLIMITED)
        val listeners = TYPES.map { (type, kind) ->
            type to object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { events.trySend(Event.Found(kind, info)) }
                override fun onServiceLost(info: NsdServiceInfo) { events.trySend(Event.Lost(kind, info.serviceName)) }
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
        }
        val found = LinkedHashMap<Pair<WirelessService.Kind, String>, WirelessService>()
        launch {
            for (event in events) {
                when (event) {
                    is Event.Found -> resolve(nsd, event.info)?.let { info ->
                        val host = ipv4(info) ?: return@let
                        found[event.kind to info.serviceName] = WirelessService(event.kind, info.serviceName, host, info.port)
                    }
                    is Event.Lost -> found.remove(event.kind to event.name)
                }
                send(found.values.toList())
            }
        }
        listeners.forEach { (type, listener) -> nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        send(emptyList())
        try {
            awaitCancellation()
        } finally {
            listeners.forEach { (_, listener) -> runCatching { nsd.stopServiceDiscovery(listener) } }
            events.close()
        }
    }.distinctUntilChanged()

    /** 이미 다른 확인이 진행 중이면(FAILURE_ALREADY_ACTIVE) 잠시 뒤 다시 한다. 끝내 못 하면 null. */
    @Suppress("DEPRECATION") // resolveService는 API 34에서 deprecated지만 26부터 쓸 수 있는 유일한 방법이다.
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        repeat(5) {
            val result = suspendCancellableCoroutine<Pair<NsdServiceInfo?, Int>> { cont ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) { if (cont.isActive) cont.resume(resolved to 0) }
                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null to errorCode) }
                })
            }
            result.first?.let { return it }
            if (result.second != NsdManager.FAILURE_ALREADY_ACTIVE) return null
            delay(200)
        }
        return null
    }

    /** adb 연결에 쓸 IPv4 주소. 링크 로컬 IPv6만 있으면 쓰지 않는다. */
    @Suppress("DEPRECATION")
    private fun ipv4(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)
        return addresses.firstOrNull { it is Inet4Address }?.hostAddress
    }

    private companion object {
        val TYPES = listOf("_adb-tls-pairing._tcp" to WirelessService.Kind.Pairing, "_adb-tls-connect._tcp" to WirelessService.Kind.Connect)
    }
}
