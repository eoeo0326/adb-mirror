package io.github.eoeo0326.adbmirror.core.domain.usecase

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.repository.WirelessDeviceRepository

/** 입력한 주소·포트·코드의 문제. 비어 있으면 시도할 수 있다. */
object WirelessInput {
    fun hostProblem(host: String): String? = "주소를 입력하세요".takeIf { host.isBlank() }

    fun portProblem(port: String): String? = port.toIntOrNull().let { p ->
        if (p == null || p !in 1..65535) "포트는 1~65535 숫자입니다" else null
    }

    fun codeProblem(code: String): String? = "페어링 코드는 6자리 숫자입니다".takeIf { code.length != 6 || !code.all(Char::isDigit) }
}

class PairDeviceUseCase(private val wireless: WirelessDeviceRepository) {
    suspend operator fun invoke(host: String, port: String, code: String) {
        val problem = WirelessInput.hostProblem(host) ?: WirelessInput.portProblem(port) ?: WirelessInput.codeProblem(code)
        require(problem == null) { problem!! }
        wireless.pair(host.trim(), port.toInt(), code)
    }
}

class ConnectWirelessDeviceUseCase(private val wireless: WirelessDeviceRepository) {
    suspend operator fun invoke(host: String, port: String): Device {
        val problem = WirelessInput.hostProblem(host) ?: WirelessInput.portProblem(port)
        require(problem == null) { problem!! }
        return wireless.connect(host.trim(), port.toInt())
    }
}

class DisconnectWirelessDeviceUseCase(private val wireless: WirelessDeviceRepository) {
    suspend operator fun invoke(serial: String) = wireless.disconnect(serial)
}
