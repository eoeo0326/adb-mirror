package io.github.eoeo0326.adbmirror.feature.inspector

import kotlin.test.Test
import kotlin.test.assertEquals

class TreeRowsTest {
    private val roots = listOf(
        node(1, "DecorView", children = arrayOf(node(2, "Column", children = arrayOf(node(3, "Text"), node(4, "Button", children = arrayOf(node(5, "Text"))))))),
        node(10, "PopupLayout", children = arrayOf(node(11, "DropdownMenu"))),
    )

    @Test
    fun listsTopWindowFirstAndHonorsCollapsed() {
        val rows = TreeRows.visible(roots, collapsed = setOf(4L), query = "")
        assertEquals(listOf(10L, 11L, 1L, 2L, 3L, 4L), rows.map { it.node.id })
        assertEquals(listOf(0, 1, 0, 1, 2, 2), rows.map { it.depth })
        val button = rows.first { it.node.id == 4L }
        assertEquals(true, button.hasChildren)
        assertEquals(false, button.expanded)
    }

    @Test
    fun searchKeepsMatchesAndAncestorsAndIgnoresCollapse() {
        val rows = TreeRows.visible(roots, collapsed = setOf(1L, 4L), query = "text")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), rows.map { it.node.id })
        assertEquals(emptyList(), TreeRows.visible(roots, emptySet(), "없는이름"))
    }
}
