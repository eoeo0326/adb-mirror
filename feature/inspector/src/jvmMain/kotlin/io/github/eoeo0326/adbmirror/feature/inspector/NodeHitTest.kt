package io.github.eoeo0326.adbmirror.feature.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode

/** 기기 디스플레이 좌표의 점이 가리키는 노드를 고른다. */
object NodeHitTest {
    /**
     * 점을 포함하는 맨 위 창(z 순서 마지막)에서, 점을 포함하는 노드 중 넓이가 가장 작은 것을 고른다.
     * 넓이가 같으면 위에 그려진 것(나중 형제·더 깊은 노드)을 고른다. 크기가 0인 노드는 고르지 않는다. 없으면 null.
     *
     * 가장 깊은 노드만 보면, 화면 전체를 덮는 투명 오버레이(디버그 도구·AndroidViewsHandler 등)가 위에 있을 때
     * 그 아래의 버튼·텍스트를 고를 수 없다.
     */
    fun hit(roots: List<LayoutNode>, x: Int, y: Int): Long? {
        for (root in roots.asReversed()) {
            var best: LayoutNode? = null
            var bestArea = Long.MAX_VALUE
            fun visit(node: LayoutNode) {
                val b = node.bounds
                if (b.width > 0 && b.height > 0 && b.contains(x, y)) {
                    val area = b.width.toLong() * b.height
                    if (area <= bestArea) {
                        best = node
                        bestArea = area
                    }
                }
                node.children.forEach(::visit)
            }
            visit(root)
            best?.let { return it.id }
        }
        return null
    }
}
