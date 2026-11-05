package io.github.eoeo0326.adbmirror.feature.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode

/** 기기 디스플레이 좌표의 점이 가리키는 노드를 고른다. */
object NodeHitTest {
    /**
     * 맨 위 창(z 순서 마지막)부터 보고, 창 안에서는 자식을 뒤(위에 그려진 것)에서 앞으로 돌며
     * 점을 포함하는 가장 깊은 노드를 고른다. 크기가 0인 노드는 고르지 않는다. 없으면 null.
     */
    fun hit(roots: List<LayoutNode>, x: Int, y: Int): Long? {
        for (root in roots.asReversed()) deepest(root, x, y)?.let { return it.id }
        return null
    }

    private fun deepest(node: LayoutNode, x: Int, y: Int): LayoutNode? {
        for (child in node.children.asReversed()) deepest(child, x, y)?.let { return it }
        return node.takeIf { it.bounds.width > 0 && it.bounds.height > 0 && it.bounds.contains(x, y) }
    }
}
