package io.github.eoeo0326.adbmirror.feature.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode

object InspectorReducer {
    /** Recomposition 강조를 유지하는 시간. */
    const val HIGHLIGHT_MS = 1_000L

    fun reduce(state: InspectorState, result: InspectorResult): InspectorState = when (result) {
        is InspectorResult.Step -> when (state.phase) {
            is InspectorPhase.Preparing -> state.copy(phase = InspectorPhase.Preparing(result.step))
            is InspectorPhase.Attaching -> state.copy(phase = InspectorPhase.Attaching(result.step))
            else -> state
        }
        is InspectorResult.Connected -> state.copy(studioLabel = result.studioLabel)
        is InspectorResult.ProcessesLoaded -> state.copy(
            processes = result.processes,
            phase = if (state.phase is InspectorPhase.Inspecting || state.phase is InspectorPhase.Attaching) state.phase else InspectorPhase.ChoosingProcess,
        )
        is InspectorResult.ProcessSelected -> state.copy(
            selectedProcess = result.process,
            phase = InspectorPhase.Attaching(null),
            roots = emptyList(),
            selectedNodeId = null,
            hoveredNodeId = null,
            collapsed = emptySet(),
            properties = PropertiesState.None,
            composeNotice = null,
            deviceError = null,
            processNotice = null,
            recentlyRecomposed = emptyMap(),
            live = true,
            showSystem = false,
        )
        is InspectorResult.SessionStarted -> state.copy(phase = InspectorPhase.Inspecting, composeVersion = result.composeVersion)
        is InspectorResult.TreeReceived -> tree(state, result)
        is InspectorResult.HighlightExpired ->
            state.copy(recentlyRecomposed = state.recentlyRecomposed.filterValues { result.nowMs - it < HIGHLIGHT_MS })
        is InspectorResult.ComposeUnavailable -> state.copy(composeNotice = result.reason)
        is InspectorResult.DeviceError -> state.copy(deviceError = result.message)
        is InspectorResult.Failed -> state.copy(phase = InspectorPhase.Failed(result.failure, result.detail))
        InspectorResult.ProcessDied -> state.copy(
            phase = InspectorPhase.ChoosingProcess,
            processNotice = "앱이 종료됐습니다",
            selectedProcess = null,
            roots = emptyList(),
            selectedNodeId = null,
            hoveredNodeId = null,
            properties = PropertiesState.None,
            recentlyRecomposed = emptyMap(),
        )
        is InspectorResult.PropertiesLoading -> state.copy(properties = PropertiesState.Loading(result.nodeId))
        is InspectorResult.PropertiesLoaded ->
            if (state.selectedNodeId == result.nodeId) state.copy(properties = PropertiesState.Loaded(result.nodeId, result.properties)) else state
        is InspectorResult.PropertiesFailed ->
            if (state.selectedNodeId == result.nodeId) state.copy(properties = PropertiesState.Failed(result.nodeId, result.message)) else state
        is InspectorResult.LiveChanged -> state.copy(live = result.live)
        is InspectorResult.SystemChanged -> state.copy(showSystem = result.show)
        InspectorResult.RecompositionColumnsToggled -> state.copy(showRecompositionColumns = !state.showRecompositionColumns)
        is InspectorResult.SearchChanged -> state.copy(search = result.query)
        is InspectorResult.NodeSelected -> state.copy(
            selectedNodeId = result.nodeId,
            properties = if (result.nodeId == null) PropertiesState.None else state.properties,
            // 화면에서 고른 노드가 접힌 부모 안에 있으면 보이도록 조상을 편다.
            collapsed = result.nodeId?.let { id -> state.collapsed - ancestors(state.roots, id) } ?: state.collapsed,
        )
        is InspectorResult.NodeHovered -> state.copy(hoveredNodeId = result.nodeId)
        is InspectorResult.ExpandToggled ->
            state.copy(collapsed = if (result.nodeId in state.collapsed) state.collapsed - result.nodeId else state.collapsed + result.nodeId)
        InspectorResult.CountsReset -> state.copy(recentlyRecomposed = emptyMap())
    }

    /** 새 트리. 이전 트리와 id별 recomposeCount를 비교해 늘어난 노드를 강조하고, 사라진 노드의 선택·hover를 푼다. */
    private fun tree(state: InspectorState, result: InspectorResult.TreeReceived): InspectorState {
        val before = state.roots.asSequence().flatMap { it.flatten() }.mapNotNull { n -> n.recomposeCount?.let { n.id to it } }.toMap()
        val ids = HashSet<Long>()
        val grown = HashMap(state.recentlyRecomposed.filterValues { result.nowMs - it < HIGHLIGHT_MS })
        result.roots.asSequence().flatMap { it.flatten() }.forEach { n ->
            ids += n.id
            val old = before[n.id]
            val now = n.recomposeCount
            if (old != null && now != null && now > old) grown[n.id] = result.nowMs
        }
        val selected = state.selectedNodeId?.takeIf { it in ids }
        return state.copy(
            roots = result.roots,
            composeAvailable = result.composeAvailable,
            selectedNodeId = selected,
            hoveredNodeId = state.hoveredNodeId?.takeIf { it in ids },
            properties = if (selected == null) PropertiesState.None else state.properties,
            lastUpdatedMs = result.nowMs,
            recentlyRecomposed = grown.filterKeys { it in ids },
        )
    }

    /** [id]의 조상 id들(자기 자신 제외). */
    fun ancestors(roots: List<LayoutNode>, id: Long): Set<Long> {
        fun walk(node: LayoutNode, path: List<Long>): List<Long>? {
            if (node.id == id) return path
            for (child in node.children) walk(child, path + node.id)?.let { return it }
            return null
        }
        return roots.firstNotNullOfOrNull { walk(it, emptyList()) }.orEmpty().toSet()
    }
}
