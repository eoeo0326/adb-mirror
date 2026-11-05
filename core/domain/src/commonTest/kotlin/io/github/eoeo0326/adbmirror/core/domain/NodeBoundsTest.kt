package io.github.eoeo0326.adbmirror.core.domain

import io.github.eoeo0326.adbmirror.core.domain.model.NodeBounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeBoundsTest {
    private val bounds = NodeBounds(x = 10, y = 20, width = 30, height = 40)

    @Test
    fun containsIncludesTopLeftAndExcludesRightBottomEdge() {
        assertTrue(bounds.contains(10, 20))
        assertTrue(bounds.contains(39, 59))
        assertFalse(bounds.contains(40, 30))
        assertFalse(bounds.contains(20, 60))
        assertFalse(bounds.contains(9, 30))
    }

    @Test
    fun emptyBoundsContainNothing() {
        assertFalse(NodeBounds(5, 5, 0, 0).contains(5, 5))
    }
}
