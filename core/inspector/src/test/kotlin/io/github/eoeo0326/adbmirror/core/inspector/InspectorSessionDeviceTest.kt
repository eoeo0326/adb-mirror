package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.adb.AdbBinaryTransport
import io.github.eoeo0326.adbmirror.core.adb.AdbLocator
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorEvent
import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실기기에서 세션 API 전체(연결 → 프로세스 → 트리·속성 → 실시간 끄기·새로 고침·초기화 → 정리)를 확인한다.
 * `-Padbmirror.device=<serial> -Padbmirror.inspectPackage=<포그라운드 debuggable 패키지>`일 때만 돈다.
 */
class InspectorSessionDeviceTest {
    @Test
    fun sessionLifecycleOnRealDevice() = runBlocking {
        val serial = System.getProperty("adbmirror.device")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val pkg = System.getProperty("adbmirror.inspectPackage")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val adb = AdbBinaryTransport.locate() ?: error("adb를 찾지 못했습니다")
        val shell = AdbDeviceShell(adb)
        val install = StudioLocator.locate(System.getProperty("adbmirror.studio")) ?: error("Android Studio를 찾지 못했습니다")
        val before = shell.shell(serial, "settings get global debug_view_attributes").trim()
        val cache = File(System.getProperty("inspector.dumpDir") ?: "build/inspector-dump", "cache")

        val transport = TransportLauncher(shell).launch(serial, install) { println("step: $it") }
        val connection = InspectorConnectionImpl(install, transport, shell, ComposeInspectorResolver(cache), log = ::println)
        try {
            val processes = connection.processes()
            println("processes: ${processes.map { it.name }}")
            val process = processes.first { it.name == pkg }
            val session = connection.inspect(process) { println("step: $it") }
            println("compose=${session.composeVersion}")

            val tree = withTimeout(20_000) { session.events.filterIsInstance<InspectorEvent.Tree>().first { it.roots.isNotEmpty() } }
            val nodes = tree.roots.flatMap { flatten(it) }
            println("roots=${tree.roots.size} nodes=${nodes.size} compose=${tree.composeAvailable}")

            val view = nodes.first { it.kind == LayoutNode.Kind.View && it.name.endsWith("View") && it.name != "DecorView" }
            val viewProps = session.properties(view)
            println("${view.name} 속성 ${viewProps.size}개: " + viewProps.take(6).joinToString { "${it.group}/${it.name}=${it.value}" })
            assertTrue(viewProps.isNotEmpty())

            if (tree.composeAvailable) {
                val composable = nodes.first { it.kind == LayoutNode.Kind.Composable && it.name == "Text" }
                val params = session.properties(composable)
                println("Text 파라미터: " + params.take(8).joinToString { "${it.group}/${it.name}=${it.value}" })
                assertTrue(params.isNotEmpty())
            }

            session.setLive(false)
            val refreshed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000) { session.events.filterIsInstance<InspectorEvent.Tree>().first() }
            }
            session.refresh()
            println("새로 고침 트리 roots=${refreshed.await().roots.size}")
            session.resetRecompositionCounts()
            session.close()
        } finally {
            connection.close()
        }
        val after = shell.shell(serial, "settings get global debug_view_attributes").trim()
        val forwards = ProcessBuilder(AdbLocator.locate()?.path ?: "adb", "forward", "--list").start().inputStream.bufferedReader().readText().trim()
        val daemon = shell.shell(serial, "pidof transport || true").trim()
        println("정리 후 forward=[$forwards] daemon=[$daemon] debug_view_attributes $before → $after")
        assertEquals(before, after)
        assertEquals("", forwards)
    }

    private fun flatten(node: LayoutNode): List<LayoutNode> = listOf(node) + node.children.flatMap(::flatten)
}
