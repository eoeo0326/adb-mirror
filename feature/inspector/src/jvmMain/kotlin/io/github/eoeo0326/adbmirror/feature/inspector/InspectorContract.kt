package io.github.eoeo0326.adbmirror.feature.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.InspectableProcess
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorFailure
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorProperty
import io.github.eoeo0326.adbmirror.core.domain.model.InspectorStep
import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode

/**
 * Layout Inspector 창 하나(기기 하나)의 MVI 계약.
 * 화면 이미지는 크고 자주 바뀌어 State에 넣지 않고 ViewModel의 별도 흐름으로 내보낸다(영상 프레임과 같은 규칙).
 */
data class InspectorState(
    val device: Device,
    val phase: InspectorPhase = InspectorPhase.Preparing(null),
    val processes: List<InspectableProcess> = emptyList(),
    val selectedProcess: InspectableProcess? = null,
    /** 창(root)들의 트리. z 순서(아래 → 위). */
    val roots: List<LayoutNode> = emptyList(),
    val composeAvailable: Boolean = false,
    val selectedNodeId: Long? = null,
    val hoveredNodeId: Long? = null,
    /** 접은 노드. 처음에는 모두 펼친다. */
    val collapsed: Set<Long> = emptySet(),
    val properties: PropertiesState = PropertiesState.None,
    val live: Boolean = true,
    val showSystem: Boolean = false,
    val showRecompositionColumns: Boolean = true,
    val search: String = "",
    /** Compose 정보를 볼 수 없는 이유(상단 배너). */
    val composeNotice: String? = null,
    /** inspector가 보낸 마지막 오류(상태줄). */
    val deviceError: String? = null,
    /** 프로세스 목록 위에 보일 안내(예: 앱이 종료됐습니다). */
    val processNotice: String? = null,
    val studioLabel: String? = null,
    val composeVersion: String? = null,
    val lastUpdatedMs: Long? = null,
    /** 직전 트리보다 Recomposition이 늘어난 노드 → 늘어난 시각(ms). 강조를 잠깐 칠했다가 지운다. */
    val recentlyRecomposed: Map<Long, Long> = emptyMap(),
) {
    val selectedNode: LayoutNode? get() = selectedNodeId?.let { id -> roots.firstNotNullOfOrNull { it.find(id) } }
}

sealed interface InspectorPhase {
    /** 기기 준비 중(Studio 찾기·push·데몬). */
    data class Preparing(val step: InspectorStep?) : InspectorPhase

    /** 프로세스를 고르는 중. 목록이 비어 있으면 안내와 다시 찾기를 보인다. */
    data object ChoosingProcess : InspectorPhase

    /** 고른 프로세스에 붙는 중. */
    data class Attaching(val step: InspectorStep?) : InspectorPhase

    data object Inspecting : InspectorPhase

    data class Failed(val failure: InspectorFailure, val detail: String?) : InspectorPhase
}

sealed interface PropertiesState {
    data object None : PropertiesState
    data class Loading(val nodeId: Long) : PropertiesState
    data class Loaded(val nodeId: Long, val properties: List<InspectorProperty>) : PropertiesState
    data class Failed(val nodeId: Long, val message: String) : PropertiesState
}

sealed interface InspectorIntent {
    data class SelectProcess(val process: InspectableProcess) : InspectorIntent
    data object ReloadProcesses : InspectorIntent
    data object ToggleLive : InspectorIntent
    data object Refresh : InspectorIntent
    data object ResetCounts : InspectorIntent
    data object ToggleSystem : InspectorIntent
    data object ToggleRecompositionColumns : InspectorIntent
    data class Search(val query: String) : InspectorIntent
    data class SelectNode(val nodeId: Long?) : InspectorIntent
    data class HoverNode(val nodeId: Long?) : InspectorIntent
    data class ToggleExpanded(val nodeId: Long) : InspectorIntent

    /** 실패 화면의 다시 시도. 실패한 단계부터 다시 한다. */
    data object Retry : InspectorIntent
    data object OpenSettings : InspectorIntent
}

/** UseCase(Repository) 실행 결과. [InspectorReducer]만 State를 바꾼다. */
sealed interface InspectorResult {
    data class Step(val step: InspectorStep) : InspectorResult
    data class Connected(val studioLabel: String) : InspectorResult
    data class ProcessesLoaded(val processes: List<InspectableProcess>) : InspectorResult
    data class ProcessSelected(val process: InspectableProcess) : InspectorResult
    data class SessionStarted(val composeVersion: String?) : InspectorResult
    data class TreeReceived(val roots: List<LayoutNode>, val composeAvailable: Boolean, val nowMs: Long) : InspectorResult

    /** [nowMs]보다 [InspectorReducer.HIGHLIGHT_MS] 이상 지난 강조를 지운다. */
    data class HighlightExpired(val nowMs: Long) : InspectorResult
    data class ComposeUnavailable(val reason: String) : InspectorResult
    data class DeviceError(val message: String) : InspectorResult
    data class Failed(val failure: InspectorFailure, val detail: String?) : InspectorResult

    /** 검사하던 프로세스가 끝났다. 프로세스 목록으로 돌아간다. */
    data object ProcessDied : InspectorResult
    data class PropertiesLoading(val nodeId: Long) : InspectorResult
    data class PropertiesLoaded(val nodeId: Long, val properties: List<InspectorProperty>) : InspectorResult
    data class PropertiesFailed(val nodeId: Long, val message: String) : InspectorResult
    data class LiveChanged(val live: Boolean) : InspectorResult
    data class SystemChanged(val show: Boolean) : InspectorResult
    data object RecompositionColumnsToggled : InspectorResult
    data class SearchChanged(val query: String) : InspectorResult
    data class NodeSelected(val nodeId: Long?) : InspectorResult
    data class NodeHovered(val nodeId: Long?) : InspectorResult
    data class ExpandToggled(val nodeId: Long) : InspectorResult
    data object CountsReset : InspectorResult
}

sealed interface InspectorEffect {
    data object OpenSettings : InspectorEffect
}

fun LayoutNode.find(id: Long): LayoutNode? = if (this.id == id) this else children.firstNotNullOfOrNull { it.find(id) }

fun LayoutNode.flatten(): Sequence<LayoutNode> = sequence {
    yield(this@flatten)
    children.forEach { yieldAll(it.flatten()) }
}
