package io.github.eoeo0326.adbmirror.core.inspector

import io.github.eoeo0326.adbmirror.core.domain.model.LayoutNode
import io.github.eoeo0326.adbmirror.core.domain.model.NodeBounds
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.ComposableNode
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.ComposableRoot
import io.github.eoeo0326.adbmirror.core.inspector.proto.compose.ComposeLayoutInspection.GetComposablesResponse
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.LayoutEvent
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.RootView
import io.github.eoeo0326.adbmirror.core.inspector.proto.view.ViewLayoutInspection.ViewNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeMapperTest {
    private val viewStrings = listOf("com.android.internal.policy", "DecorView", "androidx.compose.ui.platform", "AndroidComposeView", "android.widget", "TextView", "hello", "FrameLayout")
    private fun vs(s: String) = viewStrings.indexOf(s) + 1

    private fun view(id: Long, pkg: String, cls: String, x: Int, y: Int, w: Int, h: Int, vararg children: ViewNode, text: String? = null) =
        ViewNode.newBuilder().setId(id).setPackageName(vs(pkg)).setClassName(vs(cls))
            .setBounds(ViewLayoutInspection.Bounds.newBuilder().setLayout(ViewLayoutInspection.Rect.newBuilder().setX(x).setY(y).setW(w).setH(h)))
            .apply { text?.let { setTextValue(vs(it)) } }
            .addAllChildren(children.toList()).build()

    private fun layout(root: ViewNode, offsetX: Int = 0, offsetY: Int = 0) = LayoutEvent.newBuilder()
        .addAllStrings(viewStrings.mapIndexed { i, s -> ViewLayoutInspection.StringEntry.newBuilder().setId(i + 1).setStr(s).build() })
        .setRootView(RootView.newBuilder().setNode(root).setOffset(ViewLayoutInspection.Point.newBuilder().setX(offsetX).setY(offsetY)))
        .build()

    private val composeStrings = listOf("MyScreen", "Greeting.kt", "Text", "Layout", "AndroidView")
    private fun cs(s: String) = composeStrings.indexOf(s) + 1

    private fun composable(id: Long, name: String, x: Int, y: Int, w: Int, h: Int, vararg children: ComposableNode, line: Int = 0, recompose: Int = 0, skips: Int = 0, flags: Int = 0, viewId: Long = 0) =
        ComposableNode.newBuilder().setId(id).setName(cs(name)).setFilename(cs("Greeting.kt")).setLineNumber(line)
            .setRecomposeCount(recompose).setRecomposeSkips(skips).setFlags(flags).setViewId(viewId).setAnchorHash(id.toInt() * 10)
            .setBounds(ComposeLayoutInspection.Bounds.newBuilder().setLayout(ComposeLayoutInspection.Rect.newBuilder().setX(x).setY(y).setW(w).setH(h)))
            .addAllChildren(children.toList()).build()

    private fun composables(vararg roots: ComposableRoot) = GetComposablesResponse.newBuilder()
        .addAllStrings(composeStrings.mapIndexed { i, s -> ComposeLayoutInspection.StringEntry.newBuilder().setId(i + 1).setStr(s).build() })
        .addAllRoots(roots.toList()).build()

    @Test
    fun mapsViewTreeWithStringTable() {
        val root = view(1, "com.android.internal.policy", "DecorView", 0, 0, 1080, 2400,
            view(2, "android.widget", "TextView", 10, 20, 300, 50, text = "hello"))
        val tree = TreeMapper.map(layout(root))
        assertEquals("DecorView", tree.name)
        assertEquals("com.android.internal.policy.DecorView", tree.qualifiedName)
        assertEquals(LayoutNode.Kind.View, tree.kind)
        assertEquals(NodeBounds(0, 0, 1080, 2400), tree.bounds)
        val text = tree.children.single()
        assertEquals("TextView", text.name)
        assertEquals("hello", text.textValue)
        assertEquals(1L, text.rootId)
        assertNull(tree.textValue)
    }

    @Test
    fun attachesComposablesUnderHostViewAndSkipsViews() {
        // DecorView > FrameLayout(skip) > AndroidComposeView
        val root = view(1, "com.android.internal.policy", "DecorView", 0, 0, 1080, 2400,
            view(5, "android.widget", "FrameLayout", 0, 0, 1080, 2400,
                view(3, "androidx.compose.ui.platform", "AndroidComposeView", 0, 0, 1080, 2400)))
        val compose = composables(
            ComposableRoot.newBuilder().setViewId(3).addViewsToSkip(5).addNodes(
                composable(-100, "MyScreen", 0, 0, 1080, 2400,
                    composable(-101, "Text", 10, 10, 100, 40, line = 42, recompose = 4, skips = 8),
                    composable(-102, "Layout", 0, 0, 10, 10, flags = 1),
                    line = 12, recompose = 12, skips = 30),
            ).build(),
        )
        val tree = TreeMapper.map(layout(root), compose)
        val host = tree.children.single()
        assertEquals("AndroidComposeView", host.name, "skip된 FrameLayout 대신 그 자식이 올라온다")
        val screen = host.children.single()
        assertEquals(LayoutNode.Kind.Composable, screen.kind)
        assertEquals("MyScreen", screen.name)
        assertEquals("Greeting.kt", screen.sourceFile)
        assertEquals(12, screen.sourceLine)
        assertEquals(12, screen.recomposeCount)
        assertEquals(30, screen.recomposeSkips)
        assertEquals(-1000, screen.anchorHash)
        val (text, layoutNode) = screen.children
        assertEquals(42, text.sourceLine)
        assertEquals(4 to 8, text.recomposeCount to text.recomposeSkips)
        assertTrue(layoutNode.systemCreated)
        assertTrue(!text.systemCreated)
        assertEquals(1L, text.rootId)
    }

    @Test
    fun movesEmbeddedAndroidViewUnderItsComposable() {
        val root = view(1, "com.android.internal.policy", "DecorView", 0, 0, 1080, 2400,
            view(3, "androidx.compose.ui.platform", "AndroidComposeView", 0, 0, 1080, 2400,
                view(9, "android.widget", "TextView", 0, 100, 500, 50, text = "hello")))
        val compose = composables(
            ComposableRoot.newBuilder().setViewId(3).addNodes(composable(-1, "AndroidView", 0, 100, 500, 50, viewId = 9)).build(),
        )
        val host = TreeMapper.map(layout(root), compose).children.single()
        val androidView = host.children.single()
        assertEquals("AndroidView", androidView.name, "TextView는 원래 자리에서 빠진다")
        assertEquals("TextView", androidView.children.single().name)
    }

    @Test
    fun addsRootOffsetOnlyWhenAsked() {
        val root = view(1, "com.android.internal.policy", "DecorView", 0, 0, 500, 300,
            view(2, "android.widget", "TextView", 10, 20, 30, 40))
        assertEquals(NodeBounds(10, 20, 30, 40), TreeMapper.map(layout(root, 100, 200)).children.single().bounds)
        val shifted = TreeMapper.map(layout(root, 100, 200), addRootOffset = true)
        assertEquals(NodeBounds(100, 200, 500, 300), shifted.bounds)
        assertEquals(NodeBounds(110, 220, 30, 40), shifted.children.single().bounds)
    }

    @Test
    fun keepsRootEvenIfListedToSkip() {
        val root = view(1, "com.android.internal.policy", "DecorView", 0, 0, 10, 10)
        val compose = composables(ComposableRoot.newBuilder().setViewId(99).addViewsToSkip(1).build())
        assertEquals(1L, TreeMapper.map(layout(root), compose).id)
    }
}
