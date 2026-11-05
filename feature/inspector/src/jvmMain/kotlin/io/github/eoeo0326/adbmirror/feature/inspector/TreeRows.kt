package io.github.eoeo0326.adbmirror.feature.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode

/** 컴포넌트 트리의 한 줄. */
data class TreeRow(val node: LayoutNode, val depth: Int, val hasChildren: Boolean, val expanded: Boolean)

object TreeRows {
    /**
     * 펼친 노드만 깊이 우선으로 나열한다. [query]가 있으면 이름에 부분 일치(대소문자 무시)하는 노드와 그 조상만 남기고,
     * 그때는 접힘을 무시하고 모두 펼친다.
     */
    fun visible(roots: List<LayoutNode>, collapsed: Set<Long>, query: String): List<TreeRow> {
        val q = query.trim()
        val keep: Set<Long>? = if (q.isEmpty()) null else matchingWithAncestors(roots, q)
        val rows = ArrayList<TreeRow>()
        fun walk(node: LayoutNode, depth: Int) {
            if (keep != null && node.id !in keep) return
            val children = if (keep != null) node.children.filter { it.id in keep } else node.children
            val expanded = keep != null || node.id !in collapsed
            rows += TreeRow(node, depth, children.isNotEmpty(), expanded)
            if (expanded) children.forEach { walk(it, depth + 1) }
        }
        // 위에 있는 창(z 순서 마지막)을 먼저 보인다.
        roots.asReversed().forEach { walk(it, 0) }
        return rows
    }

    private fun matchingWithAncestors(roots: List<LayoutNode>, q: String): Set<Long> {
        val keep = HashSet<Long>()
        fun walk(node: LayoutNode): Boolean {
            var any = node.name.contains(q, ignoreCase = true)
            for (child in node.children) if (walk(child)) any = true
            if (any) keep += node.id
            return any
        }
        roots.forEach(::walk)
        return keep
    }
}
