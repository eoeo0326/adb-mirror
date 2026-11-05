package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorConnection
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorException
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorSession
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.CreateInspectorResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AgentData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 기기 하나에 준비된 transport 연결. 프로세스를 고르면 에이전트를 붙이고 View·Compose inspector를 만들어 세션을 연다.
 *
 * 정리 순서: 열린 세션(StopFetch·inspector 폐기) → `debug_view_attributes` 되돌리기 → transport(이벤트 스트림·채널·forward·띄운 데몬).
 */
class InspectorConnectionImpl(
    private val install: StudioInstall,
    private val transport: TransportConnection,
    private val shell: DeviceShell,
    private val resolver: ComposeInspectorResolver,
    private val log: (String) -> Unit = {},
) : InspectorConnection {
    override val serial: String get() = transport.serial
    override val studioLabel: String get() = install.label

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val lister = ProcessLister(shell)
    private val mutex = Mutex()
    private var session: InspectorSession? = null

    /** 우리가 바꾸기 전 `debug_view_attributes` 값. 바꾸지 않았으면 null. */
    private var previousViewAttributes: String? = null
    private val closed = AtomicBoolean(false)

    override suspend fun processes(): List<InspectableProcess> = lister.debuggable(serial, transport.abis)

    override suspend fun inspect(process: InspectableProcess, onStep: (InspectorStep) -> Unit): InspectorSession = mutex.withLock {
        check(!closed.get()) { "연결이 닫혔습니다" }
        session?.close()
        session = null
        enableViewAttributes()

        onStep(InspectorStep.Attaching)
        val status = transport.client.attachAgent(transport.deviceId, process.pid, process.packageName, process.abiCpuArch)
        if (status != AgentData.Status.ATTACHED) {
            val reason = if (status == AgentData.Status.UNATTACHABLE) "에이전트를 붙일 수 없는 프로세스입니다" else "에이전트 응답이 없습니다(시간 초과)"
            throw InspectorException(InspectorFailure.AttachFailed, reason)
        }

        onStep(InspectorStep.LoadingInspectors)
        val client = AppInspectionClient(transport.client, transport.deviceId, process.pid, scope)
        try {
            val view = ViewInspector(client)
            view.create()
            val compose = createCompose(client, onStep)
            InspectorSessionImpl(
                process = process,
                view = view,
                compose = compose.inspector,
                composeVersion = compose.version,
                composeUnavailable = compose.unavailable,
                isAlive = { lister.isAlive(serial, process.pid) },
                disconnected = transport.client.disconnected,
                onClosed = { client.close() },
                parent = job,
            ).also {
                it.start()
                session = it
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { client.close() }
            throw e
        }
    }

    private class ComposeSetup(val inspector: ComposeInspection?, val version: String?, val unavailable: String?)

    /** 앱의 Compose 버전을 확인하고 맞는 inspector를 올려 만든다. 볼 수 없으면 사유만 담는다(View 트리는 계속 본다). */
    private suspend fun createCompose(client: AppInspectionClient, onStep: (InspectorStep) -> Unit): ComposeSetup {
        val info = runCatching { client.libraryCompatibility(ComposeInspectorResolver.compatibilityTarget()).firstOrNull() }
            .getOrElse { return ComposeSetup(null, null, "Compose 버전을 확인하지 못했습니다: ${it.message}") }
        return when (val detected = resolver.interpret(info)) {
            ComposeInspectorResolver.Detected.NoCompose -> ComposeSetup(null, null, null)
            is ComposeInspectorResolver.Detected.Unavailable -> ComposeSetup(null, null, detected.reason)
            is ComposeInspectorResolver.Detected.Version -> {
                val version = detected.version
                val jar = try {
                    resolver.inspectorJar(version) { onStep(InspectorStep.DownloadingCompose(version)) }
                } catch (e: IOException) {
                    log("Compose inspector 내려받기 실패: ${e.message}")
                    return ComposeSetup(null, version, "inspector를 내려받지 못했습니다")
                }
                shell.pushDex(serial, jar, DeviceFiles.COMPOSE_INSPECTOR_JAR)
                val compose = ComposeInspector(client)
                when (val status = compose.create(DeviceFiles.COMPOSE_INSPECTOR_JAR, ComposeInspectorResolver.compatibilityTarget(version))) {
                    CreateInspectorResponse.Status.SUCCESS -> {
                        compose.applySettings()
                        ComposeSetup(compose, version, null)
                    }
                    CreateInspectorResponse.Status.LIBRARY_MISSING -> ComposeSetup(null, null, null)
                    CreateInspectorResponse.Status.APP_PROGUARDED -> ComposeSetup(null, version, "앱이 난독화돼 있습니다")
                    CreateInspectorResponse.Status.VERSION_INCOMPATIBLE -> ComposeSetup(null, version, "Compose $version 은 지원하지 않습니다")
                    else -> ComposeSetup(null, version, "Compose inspector를 만들지 못했습니다: $status")
                }
            }
        }
    }

    /** View 속성의 출처 정보가 이 값에 달려 있다. 원래 값을 기억해 [close]에서 되돌린다. */
    private suspend fun enableViewAttributes() {
        if (previousViewAttributes != null) return
        val current = shell.shell(serial, "settings get global debug_view_attributes").trim()
        if (current != "1") shell.shell(serial, "settings put global debug_view_attributes 1")
        previousViewAttributes = current
    }

    private suspend fun restoreViewAttributes() {
        when (val previous = previousViewAttributes ?: return) {
            "1" -> Unit
            "null", "" -> shell.shell(serial, "settings delete global debug_view_attributes")
            else -> shell.shell(serial, "settings put global debug_view_attributes $previous")
        }
        previousViewAttributes = null
    }

    override suspend fun close() = withContext(NonCancellable) {
        if (!closed.compareAndSet(false, true)) return@withContext
        mutex.withLock {
            runCatching { session?.close() }
            session = null
            runCatching { restoreViewAttributes() }.onFailure { log("debug_view_attributes를 되돌리지 못했습니다: ${it.message}") }
        }
        transport.close()
        scope.cancel()
    }
}
