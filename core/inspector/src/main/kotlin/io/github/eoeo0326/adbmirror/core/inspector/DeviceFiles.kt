package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AgentConfig
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.CommonConfig
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.DaemonConfig

/**
 * 기기 쪽 경로와 설정. Android Studio와 같은 경로·소켓 이름을 써서, Studio가 같은 기기에 붙어 있으면
 * 이미 도는 데몬을 함께 쓴다. `/data/local/tmp/perfd/` 파일은 다음 실행(과 Studio)이 다시 쓰도록 지우지 않는다.
 */
object DeviceFiles {
    const val DIR = "/data/local/tmp/perfd"
    const val DAEMON = "$DIR/transport"
    const val PERFA_JAR = "$DIR/perfa.jar"
    const val DAEMON_CONFIG = "$DIR/daemon.config"
    const val AGENT_CONFIG = "$DIR/agent.config"
    const val VIEW_INSPECTOR_JAR = "$DIR/layoutinspector-view-inspection.jar"
    const val COMPOSE_INSPECTOR_JAR = "$DIR/compose-ui-inspection.jar"

    /** 데몬이 여는 localabstract 소켓 이름. */
    const val SOCKET_NAME = "AndroidStudioTransport"

    /** 데몬이 기기 안에서 쓰는 주소. 호스트는 [SOCKET_NAME]을 forward해서 붙는다. */
    const val SERVICE_ADDRESS = "127.0.0.1:12389"

    /** Studio가 바이너리를 제공하는 ABI. */
    val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    private val CPU_ARCH = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "arm", "x86" to "x86", "x86_64" to "x86_64")

    /** ABI 이름을 transport가 쓰는 CPU 아키텍처 이름으로. 모르는 ABI면 null. */
    fun cpuArch(abi: String): String? = CPU_ARCH[abi]

    /** 에이전트 파일 이름. `ATTACH_AGENT` 명령의 `agent_lib_file_name`에도 그대로 쓴다. */
    fun agentFileName(cpuArch: String) = "libjvmtiagent_$cpuArch.so"

    fun agentPath(cpuArch: String) = "$DIR/${agentFileName(cpuArch)}"

    /** `getprop ro.product.cpu.abilist` 출력에서 지원하는 ABI만 기기 순서대로 고른다. 첫 것이 데몬 ABI다. */
    fun supportedAbis(abiList: String): List<String> =
        abiList.trim().split(',').map { it.trim() }.filter { it in CPU_ARCH }.distinct()

    private fun common(): CommonConfig = CommonConfig.newBuilder()
        .setSocketType(CommonConfig.SocketType.ABSTRACT_SOCKET)
        .setServiceAddress(SERVICE_ADDRESS)
        .setServiceSocketName(SOCKET_NAME)
        .build()

    fun daemonConfig(): ByteArray = DaemonConfig.newBuilder().setCommon(common()).build().toByteArray()

    fun agentConfig(): ByteArray = AgentConfig.newBuilder().setCommon(common()).build().toByteArray()
}
