package io.github.eoeo0326.adbmirror.core.domain.model

/** 같은 네트워크에서 mDNS로 찾은 무선 디버깅 서비스. 기기의 무선 디버깅 화면이 켜져 있는 동안 알린다. */
data class WirelessService(val kind: Kind, val name: String, val host: String, val port: Int) {
    enum class Kind {
        /** "페어링 코드로 기기 페어링" 창이 열려 있는 동안만 나타난다. */
        Pairing,

        /** 무선 디버깅 화면의 "IP 주소 및 포트". 무선 디버깅을 다시 켜면 포트가 바뀐다. */
        Connect,
    }
}

/** 연결했던 무선 기기 주소. */
data class WirelessEndpoint(val host: String, val port: Int)

/** 연결했던 기기에 다시 붙을 주소를 고른다. */
object WirelessReconnect {
    /**
     * [known] 기기마다, 같은 호스트의 연결 서비스를 찾았으면 그 포트를, 아니면 기억한 포트를 쓴다.
     * 이미 연결된 기기([connectedSerials], `호스트:포트`)와 이번 실행에서 시도한 주소([tried])는 뺀다.
     */
    fun targets(
        known: List<WirelessEndpoint>,
        services: List<WirelessService>,
        connectedSerials: Set<String>,
        tried: Set<WirelessEndpoint>,
    ): List<WirelessEndpoint> {
        val connectedHosts = connectedSerials.mapNotNull { serial -> serial.substringBeforeLast(':', "").takeIf { it.isNotEmpty() } }.toSet()
        return known.filter { it.host !in connectedHosts }.map { k ->
            val port = services.firstOrNull { it.kind == WirelessService.Kind.Connect && it.host == k.host }?.port ?: k.port
            WirelessEndpoint(k.host, port)
        }.filter { it !in tried }
    }
}

/** 알림 등으로 받은 페어링 코드를 쓸 서비스를 고른다. */
object WirelessPairing {
    /** 이 기기 주소([localHosts])의 페어링 서비스를 먼저, 없으면 페어링 서비스가 하나뿐일 때 그것. */
    fun choose(services: List<WirelessService>, localHosts: Set<String>): WirelessService? {
        val pairing = services.filter { it.kind == WirelessService.Kind.Pairing }
        return pairing.firstOrNull { it.host in localHosts } ?: pairing.singleOrNull()
    }
}
