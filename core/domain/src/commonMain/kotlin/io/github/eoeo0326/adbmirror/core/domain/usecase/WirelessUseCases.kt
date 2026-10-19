package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessEndpoint
import io.github.eoeo0326.adbmirror.core.domain.model.WirelessService
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDeviceRepository
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDiscoveryRepository
import kotlinx.coroutines.flow.Flow

/** 입력한 주소·포트·코드의 문제. 비어 있으면 시도할 수 있다. */
object WirelessInput {
    /** 주소 끝자리만 입력하면 붙이는 앞부분. 집·사무실 공유기에서 흔한 대역이다. */
    const val DEFAULT_SUBNET = "192.168.0."

    /** 숫자만(1~3자리) 입력하면 [DEFAULT_SUBNET] 뒤에 붙이고, 아니면 입력한 주소를 그대로 쓴다. */
    fun resolveHost(host: String): String {
        val trimmed = host.trim()
        return if (trimmed.isLastOctet()) DEFAULT_SUBNET + trimmed.toInt() else trimmed
    }

    fun hostProblem(host: String): String? {
        val trimmed = host.trim()
        return when {
            trimmed.isEmpty() -> "주소를 입력하세요"
            trimmed.isLastOctet() && trimmed.toInt() !in 0..255 -> "끝자리는 0~255 숫자입니다"
            else -> null
        }
    }

    private fun String.isLastOctet() = length in 1..3 && all(Char::isDigit)

    fun portProblem(port: String): String? = port.toIntOrNull().let { p ->
        if (p == null || p !in 1..65535) "포트는 1~65535 숫자입니다" else null
    }

    fun codeProblem(code: String): String? = "페어링 코드는 6자리 숫자입니다".takeIf { code.length != 6 || !code.all(Char::isDigit) }
}

class PairDeviceUseCase(private val wireless: WirelessDeviceRepository) {
    suspend operator fun invoke(host: String, port: String, code: String) {
        val problem = WirelessInput.hostProblem(host) ?: WirelessInput.portProblem(port) ?: WirelessInput.codeProblem(code)
        require(problem == null) { problem!! }
        wireless.pair(WirelessInput.resolveHost(host), port.toInt(), code)
    }
}

class ConnectWirelessDeviceUseCase(private val wireless: WirelessDeviceRepository) {
    suspend operator fun invoke(host: String, port: String): Device {
        val problem = WirelessInput.hostProblem(host) ?: WirelessInput.portProblem(port)
        require(problem == null) { problem!! }
        return wireless.connect(WirelessInput.resolveHost(host), port.toInt())
    }
}

class DisconnectWirelessDeviceUseCase(private val wireless: WirelessDeviceRepository) {
    suspend operator fun invoke(serial: String) = wireless.disconnect(serial)
}

class GetKnownWirelessDevicesUseCase(private val wireless: WirelessDeviceRepository) {
    suspend operator fun invoke(): List<WirelessEndpoint> = wireless.known()
}

class DiscoverWirelessServicesUseCase(private val discovery: WirelessDiscoveryRepository) {
    operator fun invoke(): Flow<List<WirelessService>> = discovery.services()
}
