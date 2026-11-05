package io.github.eoeo0326.adbmirror.feature.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorProperty
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import io.github.eoeo0326.adbmirror.core.domain.model.NodeBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal fun node(id: Long, name: String = "N$id", recomp: Int? = null, vararg children: LayoutNode, bounds: NodeBounds = NodeBounds(0, 0, 100, 100), kind: LayoutNode.Kind = LayoutNode.Kind.Composable) =
    LayoutNode(id, kind, name, null, bounds, children.toList(), rootId = 1, recomposeCount = recomp, recomposeSkips = recomp?.let { 0 })

class InspectorReducerTest {
    private val initial = InspectorState(Device("S1", DeviceState.Online, "Pixel"))
    private fun InspectorState.apply(vararg results: InspectorResult) = results.fold(this) { s, r -> InspectorReducer.reduce(s, r) }
    private val process = InspectableProcess(1, "com.example", "com.example", "arm64")

    @Test
    fun followsPhasesFromPreparingToInspecting() {
        val s = initial.apply(
            InspectorResult.Step(InspectorStep.PushingFiles),
            InspectorResult.Connected("Android Studio 2026.2.1"),
            InspectorResult.ProcessesLoaded(listOf(process)),
            InspectorResult.ProcessSelected(process),
            InspectorResult.Step(InspectorStep.Attaching),
        )
        assertEquals(InspectorPhase.Attaching(InspectorStep.Attaching), s.phase)
        assertEquals("Android Studio 2026.2.1", s.studioLabel)
        val inspecting = s.apply(InspectorResult.SessionStarted("1.7.8"))
        assertEquals(InspectorPhase.Inspecting, inspecting.phase)
        assertEquals("1.7.8", inspecting.composeVersion)
        // 검사 중에 목록을 다시 받아도 화면은 그대로다.
        assertEquals(InspectorPhase.Inspecting, inspecting.apply(InspectorResult.ProcessesLoaded(emptyList())).phase)
    }

    @Test
    fun highlightsNodesWhoseRecompositionGrewAndExpiresThem() {
        val first = initial.apply(InspectorResult.TreeReceived(listOf(node(1, recomp = 1, children = arrayOf(node(2, recomp = 5), node(3, recomp = 0)))), true, nowMs = 1_000))
        assertEquals(emptyMap(), first.recentlyRecomposed, "처음 받은 트리는 비교 대상이 없다")
        val second = first.apply(InspectorResult.TreeReceived(listOf(node(1, recomp = 1, children = arrayOf(node(2, recomp = 6), node(3, recomp = 0), node(4, recomp = 9)))), true, nowMs = 1_500))
        assertEquals(mapOf(2L to 1_500L), second.recentlyRecomposed, "새로 생긴 노드(4)는 강조하지 않는다")
        assertEquals(1_500L, second.lastUpdatedMs)
        assertEquals(mapOf(2L to 1_500L), second.apply(InspectorResult.HighlightExpired(2_400)).recentlyRecomposed)
        assertEquals(emptyMap(), second.apply(InspectorResult.HighlightExpired(2_500)).recentlyRecomposed)
    }

    @Test
    fun clearsSelectionWhenNodeVanishes() {
        val s = initial.apply(
            InspectorResult.TreeReceived(listOf(node(1, children = arrayOf(node(2)))), false, 0),
            InspectorResult.NodeSelected(2),
            InspectorResult.PropertiesLoaded(2, listOf(InspectorProperty("g", "a", "b"))),
            InspectorResult.NodeHovered(2),
        )
        assertEquals(2L, s.selectedNode?.id)
        val gone = s.apply(InspectorResult.TreeReceived(listOf(node(1)), false, 10))
        assertNull(gone.selectedNodeId)
        assertNull(gone.hoveredNodeId)
        assertEquals(PropertiesState.None, gone.properties)
    }

    @Test
    fun ignoresPropertiesForNodeNoLongerSelected() {
        val s = initial.apply(
            InspectorResult.TreeReceived(listOf(node(1, children = arrayOf(node(2), node(3)))), false, 0),
            InspectorResult.NodeSelected(2),
            InspectorResult.PropertiesLoading(2),
            InspectorResult.NodeSelected(3),
            InspectorResult.PropertiesLoaded(2, listOf(InspectorProperty("g", "old", "x"))),
        )
        assertEquals(PropertiesState.Loading(2), s.properties)
    }

    @Test
    fun selectingFromScreenExpandsCollapsedAncestors() {
        val s = initial.apply(
            InspectorResult.TreeReceived(listOf(node(1, children = arrayOf(node(2, children = arrayOf(node(3)))))), false, 0),
            InspectorResult.ExpandToggled(1),
            InspectorResult.ExpandToggled(2),
        )
        assertEquals(setOf(1L, 2L), s.collapsed)
        assertEquals(emptySet(), s.apply(InspectorResult.NodeSelected(3)).collapsed)
    }

    @Test
    fun processDeathReturnsToProcessList() {
        val s = initial.apply(
            InspectorResult.ProcessSelected(process),
            InspectorResult.SessionStarted(null),
            InspectorResult.TreeReceived(listOf(node(1)), false, 0),
            InspectorResult.ProcessDied,
        )
        assertEquals(InspectorPhase.ChoosingProcess, s.phase)
        assertEquals("앱이 종료됐습니다", s.processNotice)
        assertEquals(emptyList(), s.roots)
        assertNull(s.selectedProcess)
    }
}
