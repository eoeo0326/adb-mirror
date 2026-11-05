package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.adb.AdbLocator
import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import io.github.eoeo0326.adbmirror.core.inspector.proto.AppInspection.CreateInspectorResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.Transport.AgentData
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.ComposableNode
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.LayoutEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실기기에서 트리와 Recomposition 횟수를 받아 출력한다(PoC 확인용).
 *
 * ```
 * ./gradlew :core:inspector:test --tests '*InspectorDeviceDumpTest*' \
 *   -Padbmirror.device=<serial> -Padbmirror.inspectPackage=<debuggable 패키지> \
 *   [-Padbmirror.studio=<설치 경로>] [-Pinspector.tapAt=<x>,<y>]
 * ```
 * `tapAt`을 주면 그 좌표를 탭 → 뒤로 → 탭한 뒤 바뀐 트리를 다시 받아(시스템 컴포저블 포함) 횟수가 0이 아닌 Composable을 출력한다.
 * 스크린샷은 `core/inspector/build/inspector-dump/`에 PNG로 남는다.
 */
class InspectorDeviceDumpTest {
    @Test
    fun dumpsTreeFromRealDevice() = runBlocking {
        val serial = System.getProperty("adbmirror.device")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val pkg = System.getProperty("adbmirror.inspectPackage")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val tapAt = System.getProperty("inspector.tapAt")?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim().toInt() }
        val install = StudioLocator.locate(System.getProperty("adbmirror.studio")) ?: error("Android Studio를 찾지 못했습니다")
        val adb = AdbBinaryTransport.locate() ?: error("adb를 찾지 못했습니다")
        val shell = AdbDeviceShell(adb)
        val outDir = File(System.getProperty("inspector.dumpDir") ?: "build/inspector-dump").apply { mkdirs() }
        println("Studio: $install")

        val connection = TransportLauncher(shell, log = { println("launcher: $it") }).launch(serial, install) { println("step: $it") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val previousAttributes = shell.shell(serial, "settings get global debug_view_attributes").trim()
        try {
            println("deviceId=${connection.deviceId} reusedDaemon=${connection.reusedDaemon} api=${connection.apiLevel} abis=${connection.abis}")
            if (previousAttributes != "1") shell.shell(serial, "settings put global debug_view_attributes 1")

            val process = findOrLaunch(connection, shell, serial, pkg)
            println("process: pid=${process.pid} name=${process.name} abi=${process.abiCpuArch} foreground=${process.foreground}")
            // 창이 없으면(백그라운드) 빈 layout 이벤트만 온다. 앞으로 가져온다.
            if (!process.foreground) {
                shell.shell(serial, "monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true")
                delay(1_500)
            }

            val status = connection.client.attachAgent(connection.deviceId, process.pid, process.packageName, process.abiCpuArch)
            println("agent: $status")
            assertEquals(AgentData.Status.ATTACHED, status)

            val client = AppInspectionClient(connection.client, connection.deviceId, process.pid, scope)
            val view = ViewInspector(client)
            view.create()
            println("view inspector: ${DeviceFiles.VIEW_INSPECTOR_JAR}에서 생성")

            val resolver = ComposeInspectorResolver(outDir.resolve("cache"))
            val info = client.libraryCompatibility(ComposeInspectorResolver.compatibilityTarget()).firstOrNull()
            println("compose compatibility: status=${info?.status} version='${info?.version}' error='${info?.errorMessage}'")
            var compose: ComposeInspector? = null
            when (val detected = resolver.interpret(info)) {
                is ComposeInspectorResolver.Detected.Version -> {
                    val jar = resolver.inspectorJar(detected.version) { println("downloading compose ${detected.version}") }
                    shell.pushDex(serial, jar, DeviceFiles.COMPOSE_INSPECTOR_JAR)
                    val c = ComposeInspector(client)
                    val created = c.create(DeviceFiles.COMPOSE_INSPECTOR_JAR, ComposeInspectorResolver.compatibilityTarget(detected.version))
                    println("compose inspector: $created")
                    if (created == CreateInspectorResponse.Status.SUCCESS) {
                        val settings = c.updateSettings()
                        println("compose settings: unknownCommand=${settings.hasUnknownCommandResponse()}")
                        compose = c
                    }
                }
                else -> println("compose: $detected")
            }

            view.useBitmapScreenshots()
            // BITMAP 스크린샷은 실시간(continuous) fetch에서만 온다. 구독을 먼저 걸고 fetch한다.
            val first = async(start = CoroutineStart.UNDISPATCHED) { collectLayouts(view, untilAllRoots = true) }
            val fetch = view.startFetch(continuous = true)
            if (fetch.error.isNotEmpty()) println("startFetch error: ${fetch.code} ${fetch.error}")
            val layouts = first.await()
            assertTrue(layouts.isNotEmpty(), "layout 이벤트를 받지 못했습니다")

            var generation = 0
            layouts.values.forEach { layout ->
                val root = layout.rootView
                println("\n== root ${root.node.id} offset=(${root.offset.x},${root.offset.y}) display=${root.displayId} bounds=${root.node.bounds.layout.let { "${it.x},${it.y} ${it.w}x${it.h}" }}")
                val composables = compose?.composables(root.node.id, skipSystem = true, generation = ++generation, allowEmptyIfUnchanged = false)?.getComposablesResponse
                printTree(TreeMapper.map(layout, composables))
                saveScreenshot(layout, outDir)
            }

            if (tapAt != null && compose != null) {
                val next = async(start = CoroutineStart.UNDISPATCHED) { collectLayouts(view, untilAllRoots = false, windowMs = 3_000) }
                println("\ntap ${tapAt[0]},${tapAt[1]}")
                shell.shell(serial, "input tap ${tapAt[0]} ${tapAt[1]}")
                delay(800)
                shell.shell(serial, "input keyevent KEYCODE_BACK")
                delay(800)
                shell.shell(serial, "input tap ${tapAt[0]} ${tapAt[1]}")
                val changed = next.await()
                println("탭 뒤 바뀐 루트: ${changed.keys}")
                changed.values.forEach { layout ->
                    val composables = compose.composables(layout.rootView.node.id, skipSystem = false, generation = ++generation, allowEmptyIfUnchanged = false).getComposablesResponse
                    val nodes = composables.rootsList.flatMap { it.nodesList }.flatMap(::flatten)
                    println("Composable ${nodes.size}개 중 recomp>0 ${nodes.count { it.recomposeCount > 0 }}개, skip>0 ${nodes.count { it.recomposeSkips > 0 }}개")
                    val tree = TreeMapper.map(layout, composables)
                    println("== root ${layout.rootView.node.id} (${tree.bounds}) 횟수가 0이 아닌 Composable:")
                    printCounted(tree)
                    saveScreenshot(layout, outDir)
                }
                shell.shell(serial, "input keyevent KEYCODE_BACK")
            }

            runCatching { view.stopFetch() }
            runCatching { compose?.dispose() }.onFailure { println("compose dispose: $it") }
            runCatching { view.dispose() }.onFailure { println("view dispose: $it") }
            client.close()
        } finally {
            scope.cancel()
            connection.close()
            when (previousAttributes) {
                "1" -> Unit
                "null", "" -> shell.shell(serial, "settings delete global debug_view_attributes")
                else -> shell.shell(serial, "settings put global debug_view_attributes $previousAttributes")
            }
            println("\n정리 후 forward: [${forwardList()}] debug_view_attributes=${shell.shell(serial, "settings get global debug_view_attributes").trim()}")
        }
    }

    /** roots 이벤트를 따라 루트별 최신 layout 이벤트를 모은다. [untilAllRoots]면 모든 루트가 모이면 끝, 아니면 [windowMs] 동안 모은다. */
    private suspend fun collectLayouts(view: ViewInspector, untilAllRoots: Boolean, windowMs: Long = 20_000): Map<Long, LayoutEvent> {
        val layouts = LinkedHashMap<Long, LayoutEvent>()
        var roots: List<Long>? = null
        withTimeoutOrNull(windowMs) {
            view.events.takeWhile { e ->
                when {
                    e.hasRootsEvent() -> roots = e.rootsEvent.idsList.also { ids -> layouts.keys.retainAll(ids.toSet()) }
                    e.hasLayoutEvent() -> layouts[e.layoutEvent.rootView.node.id] = e.layoutEvent
                    e.hasErrorEvent() -> println("view error: ${e.errorEvent.message}")
                }
                val r = roots
                !untilAllRoots || r == null || !layouts.keys.containsAll(r)
            }.toList()
        }
        return layouts
    }

    private fun saveScreenshot(layout: LayoutEvent, outDir: File) {
        val bytes = layout.screenshot.bytes
        if (bytes.isEmpty) return println("screenshot 없음 (type=${layout.screenshot.type})")
        val shot = BitmapDecoder.decode(layout.rootView.node.id, bytes.toByteArray())
        val image = BufferedImage(shot.width, shot.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, shot.width, shot.height, shot.argb, 0, shot.width)
        val file = File(outDir, "root-${shot.rootId}.png")
        ImageIO.write(image, "png", file)
        println("screenshot ${shot.width}x${shot.height} → ${file.absolutePath}")
    }

    private suspend fun findOrLaunch(connection: TransportConnection, shell: DeviceShell, serial: String, pkg: String): InspectableProcess {
        val lister = ProcessLister(shell)
        suspend fun target() = lister.debuggable(serial, connection.abis).also { list ->
            println("debuggable: " + list.joinToString { "${it.name}(${it.pid},${it.abiCpuArch}${if (it.foreground) ",fg" else ""})" })
        }.firstOrNull { it.name == pkg }
        target()?.let { return it }
        println("$pkg 실행")
        shell.shell(serial, "monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true")
        return withTimeout(15_000) {
            var found: InspectableProcess? = null
            while (found == null) {
                delay(500)
                found = target()
            }
            found
        }
    }

    private fun forwardList(): String = runCatching {
        val adb = AdbLocator.locate()?.path ?: "adb"
        ProcessBuilder(adb, "forward", "--list").start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("?")

    private fun printTree(node: LayoutNode, depth: Int = 0) {
        if (depth > 60) return
        val counts = if (node.kind == LayoutNode.Kind.Composable) "  [recomp ${node.recomposeCount} skip ${node.recomposeSkips}]" else ""
        val source = node.sourceFile?.let { "  $it:${node.sourceLine}" }.orEmpty()
        val text = node.textValue?.let { "  \"${it.take(30)}\"" }.orEmpty()
        val b = node.bounds
        println("  ".repeat(depth) + "${node.name} (${b.x},${b.y} ${b.width}x${b.height})$counts$source$text")
        node.children.forEach { printTree(it, depth + 1) }
    }

    private fun flatten(node: ComposableNode): List<ComposableNode> = listOf(node) + node.childrenList.flatMap(::flatten)

    private fun printCounted(node: LayoutNode) {
        if ((node.recomposeCount ?: 0) > 0 || (node.recomposeSkips ?: 0) > 0) {
            println("  ${node.name}  recomp ${node.recomposeCount} skip ${node.recomposeSkips}  ${node.sourceFile}:${node.sourceLine}")
        }
        node.children.forEach(::printCounted)
    }
}
