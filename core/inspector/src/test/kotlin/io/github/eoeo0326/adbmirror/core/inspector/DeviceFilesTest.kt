package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AgentConfig
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.CommonConfig
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.DaemonConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceFilesTest {
    @Test
    fun mapsAbiToCpuArch() {
        assertEquals("arm64", DeviceFiles.cpuArch("arm64-v8a"))
        assertEquals("arm", DeviceFiles.cpuArch("armeabi-v7a"))
        assertEquals("x86", DeviceFiles.cpuArch("x86"))
        assertEquals("x86_64", DeviceFiles.cpuArch("x86_64"))
        assertNull(DeviceFiles.cpuArch("armeabi"))
        assertEquals("/data/local/tmp/perfd/libjvmtiagent_arm64.so", DeviceFiles.agentPath("arm64"))
    }

    @Test
    fun supportedAbisKeepsDeviceOrderAndDropsUnknown() {
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), DeviceFiles.supportedAbis("arm64-v8a,armeabi-v7a,armeabi\n"))
        assertEquals(listOf("x86_64", "x86"), DeviceFiles.supportedAbis(" x86_64 , x86 "))
        assertEquals(emptyList(), DeviceFiles.supportedAbis(""))
    }

    @Test
    fun configsShareCommonAbstractSocketSettings() {
        val daemon = DaemonConfig.parseFrom(DeviceFiles.daemonConfig()).common
        val agent = AgentConfig.parseFrom(DeviceFiles.agentConfig()).common
        assertEquals(daemon, agent)
        assertEquals(CommonConfig.SocketType.ABSTRACT_SOCKET, daemon.socketType)
        assertEquals("127.0.0.1:12389", daemon.serviceAddress)
        assertEquals("AndroidStudioTransport", daemon.serviceSocketName)
    }
}
