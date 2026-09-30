package io.github.eoeo0326.adbmirror

import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowBoundsTest {
    private val file = File(Files.createTempDirectory("adbmirror-win").toFile(), "sub/windows.properties")

    @Test
    fun storesAndReloadsPerKey() {
        val store = WindowBoundsStore(file)
        store.put("list", SavedBounds(10, 20, 420, 560))
        store.put("mirror.192.168.0.5:5555", SavedBounds(-1200, 40))
        val reloaded = WindowBoundsStore(file)
        assertEquals(SavedBounds(10, 20, 420, 560), reloaded.get("list"))
        assertEquals(SavedBounds(-1200, 40), reloaded.get("mirror.192.168.0.5:5555"))
        assertNull(reloaded.get("mirror.other"))
        assertEquals(listOf("windows.properties"), file.parentFile.list()!!.toList())
    }

    @Test
    fun brokenValuesAreIgnored() {
        file.parentFile.mkdirs()
        file.writeText("list.x=abc\nlist.y=10\n")
        assertNull(WindowBoundsStore(file).get("list"))
    }

    @Test
    fun reachableOnlyWhenTitleAreaIsOnSomeScreen() {
        val main = Rectangle(0, 0, 1920, 1080)
        val left = Rectangle(-1280, 0, 1280, 1024)
        assertTrue(isReachable(SavedBounds(100, 100, 420, 560), listOf(main), 420))
        assertTrue(isReachable(SavedBounds(-800, 50), listOf(main, left), 420))
        // 왼쪽 모니터를 떼어 낸 뒤
        assertFalse(isReachable(SavedBounds(-800, 50), listOf(main), 420))
        // 거의 오른쪽 끝 밖(보이는 폭 50px < 100px)
        assertFalse(isReachable(SavedBounds(1870, 100, 420, 560), listOf(main), 420))
        // 화면 아래로 빠짐
        assertFalse(isReachable(SavedBounds(100, 1060, 420, 560), listOf(main), 420))
    }
}
