package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import io.github.eoeo0326.adbmirror.core.domain.model.NodeBounds
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.ComposableNode
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.ComposableRoot
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.GetComposablesResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.LayoutEvent
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.ViewNode
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.StringEntry as ViewString

/**
 * View inspector의 [LayoutEvent](창 하나)와 Compose inspector의 [GetComposablesResponse]를 한 [LayoutNode] 트리로 합친다.
 *
 * - 이름 필드(`int32`)는 각 메시지의 문자열 테이블 id다. 테이블은 이벤트·응답마다 새로 온다.
 * - Compose 노드는 `ComposableRoot.view_id`와 같은 id의 View(보통 `AndroidComposeView`) 자식으로 붙인다.
 * - Composable의 `view_id`가 가리키는 View(AndroidView가 품은 View)는 그 Composable 아래로 옮긴다.
 * - `views_to_skip`의 View는 빼고, 그 자식을 부모에게 올린다.
 */
object TreeMapper {
    private const val FLAG_SYSTEM_CREATED = 1

    /**
     * @param addRootOffset `RootView.offset`을 모든 bounds에 더할지. 좌표계가 창 기준이면 true로 디스플레이 좌표에 맞춘다.
     */
    fun map(layout: LayoutEvent, compose: GetComposablesResponse? = null, addRootOffset: Boolean = false): LayoutNode {
        val root = layout.rootView
        val rootId = root.node.id
        val dx = if (addRootOffset) root.offset.x else 0
        val dy = if (addRootOffset) root.offset.y else 0
        val viewStrings = strings(layout.stringsList)
        val composeStrings = compose?.let { c -> c.stringsList.associate { it.id to it.str } }.orEmpty()
        val composeRoots = compose?.rootsList.orEmpty().associateBy { it.viewId }
        val skip = compose?.rootsList.orEmpty().flatMap { it.viewsToSkipList }.toSet()

        // AndroidView가 품은 View는 원래 자리에서 빼서 그 Composable 아래로 옮긴다.
        val viewsById = HashMap<Long, ViewNode>()
        fun index(node: ViewNode) {
            viewsById[node.id] = node
            node.childrenList.forEach(::index)
        }
        index(root.node)
        val embedded = compose?.rootsList.orEmpty().flatMap { r -> r.nodesList.flatMap(::embeddedViewIds) }.toSet()

        val context = Context(rootId, dx, dy, viewStrings, composeStrings, composeRoots, skip, embedded, viewsById)
        // 루트 자체가 skip 대상이어도 창 하나는 노드 하나로 남긴다.
        return context.view(root.node, keepEvenIfSkipped = true).single()
    }

    private class Context(
        val rootId: Long,
        val dx: Int,
        val dy: Int,
        val viewStrings: Map<Int, String>,
        val composeStrings: Map<Int, String>,
        val composeRoots: Map<Long, ComposableRoot>,
        val skip: Set<Long>,
        val embedded: Set<Long>,
        val viewsById: Map<Long, ViewNode>,
    ) {
        fun view(node: ViewNode, keepEvenIfSkipped: Boolean = false): List<LayoutNode> {
            val viewChildren = node.childrenList.filter { it.id !in embedded }.flatMap { view(it) }
            val composeChildren = composeRoots[node.id]?.nodesList?.map(::composable).orEmpty()
            val children = composeChildren + viewChildren
            if (node.id in skip && !keepEvenIfSkipped) return children

            val className = viewStrings[node.className].orEmpty()
            val packageName = viewStrings[node.packageName].orEmpty()
            return listOf(
                LayoutNode(
                    id = node.id,
                    kind = LayoutNode.Kind.View,
                    name = className.ifEmpty { "View" },
                    qualifiedName = if (packageName.isEmpty()) className.ifEmpty { null } else "$packageName.$className",
                    bounds = bounds(node.bounds.layout.x, node.bounds.layout.y, node.bounds.layout.w, node.bounds.layout.h),
                    children = children,
                    rootId = rootId,
                    textValue = viewStrings[node.textValue]?.takeIf { node.textValue != 0 && it.isNotEmpty() },
                ),
            )
        }

        fun composable(node: ComposableNode): LayoutNode {
            val hosted = if (node.viewId != 0L) viewsById[node.viewId]?.let { view(it) }.orEmpty() else emptyList()
            val rect = node.bounds.layout
            return LayoutNode(
                id = node.id,
                kind = LayoutNode.Kind.Composable,
                name = composeStrings[node.name].orEmpty().ifEmpty { "Composable" },
                qualifiedName = null,
                bounds = bounds(rect.x, rect.y, rect.w, rect.h),
                children = node.childrenList.map(::composable) + hosted,
                rootId = rootId,
                sourceFile = composeStrings[node.filename]?.takeIf { node.filename != 0 && it.isNotEmpty() },
                sourceLine = node.lineNumber.takeIf { it > 0 },
                recomposeCount = node.recomposeCount,
                recomposeSkips = node.recomposeSkips,
                anchorHash = node.anchorHash,
                systemCreated = node.flags and FLAG_SYSTEM_CREATED != 0,
            )
        }

        fun bounds(x: Int, y: Int, w: Int, h: Int) = NodeBounds(x + dx, y + dy, w, h)
    }

    private fun embeddedViewIds(node: ComposableNode): List<Long> =
        listOfNotNull(node.viewId.takeIf { it != 0L }) + node.childrenList.flatMap(::embeddedViewIds)

    private fun strings(entries: List<ViewString>): Map<Int, String> = entries.associate { it.id to it.str }
}
