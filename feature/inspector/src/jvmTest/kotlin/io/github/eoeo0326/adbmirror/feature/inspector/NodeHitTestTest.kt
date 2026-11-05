package io.github.eoeo0326.adbmirror.feature.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.NodeBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeHitTestTest {
    private val window = node(
        1, bounds = NodeBounds(0, 0, 1000, 2000),
        children = arrayOf(
            node(2, bounds = NodeBounds(0, 0, 1000, 500), children = arrayOf(node(3, bounds = NodeBounds(500, 100, 200, 100)))),
            // 2 위에 겹쳐 그려진 형제
            node(4, bounds = NodeBounds(50, 50, 400, 400)),
            node(5, bounds = NodeBounds(0, 0, 0, 0)),
        ),
    )
    private val popup = node(10, bounds = NodeBounds(600, 600, 300, 300), children = arrayOf(node(11, bounds = NodeBounds(650, 650, 100, 100))))

    @Test
    fun picksDeepestFrontmostNode() {
        assertEquals(4L, NodeHitTest.hit(listOf(window), 150, 150), "나중 형제가 위에 그려진다")
        assertEquals(3L, NodeHitTest.hit(listOf(window), 550, 180))
        assertEquals(2L, NodeHitTest.hit(listOf(window), 900, 100))
        assertEquals(1L, NodeHitTest.hit(listOf(window), 900, 1500))
        assertNull(NodeHitTest.hit(listOf(window), 1500, 1500))
    }

    @Test
    fun topWindowWinsAcrossRoots() {
        assertEquals(11L, NodeHitTest.hit(listOf(window, popup), 700, 700))
        assertEquals(10L, NodeHitTest.hit(listOf(window, popup), 850, 850))
        assertEquals(1L, NodeHitTest.hit(listOf(window, popup), 950, 1500))
    }

    @Test
    fun ignoresZeroSizedNodes() {
        assertEquals(4L, NodeHitTest.hit(listOf(window), 50, 50))
        assertEquals(2L, NodeHitTest.hit(listOf(window), 0, 0))
    }

    @Test
    fun smallerNodeBehindFullScreenOverlayWins() {
        // 같은 창 안에서 나중 형제(위)인 전체 화면 오버레이가 앞 형제의 버튼을 덮는다.
        val content = node(20, bounds = NodeBounds(0, 0, 1000, 2000), children = arrayOf(node(21, "Button", bounds = NodeBounds(100, 1600, 800, 200))))
        val overlay = node(30, bounds = NodeBounds(0, 0, 1000, 2000), children = arrayOf(node(31, "Fab", bounds = NodeBounds(900, 1000, 80, 80))))
        val window = node(1, bounds = NodeBounds(0, 0, 1000, 2000), children = arrayOf(content, overlay))
        assertEquals(21L, NodeHitTest.hit(listOf(window), 500, 1700))
        assertEquals(31L, NodeHitTest.hit(listOf(window), 930, 1030))
        // 넓이가 같으면 위에 그려진 쪽.
        assertEquals(30L, NodeHitTest.hit(listOf(window), 500, 500))
    }
}
