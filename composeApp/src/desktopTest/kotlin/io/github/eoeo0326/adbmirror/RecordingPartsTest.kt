package io.github.eoeo0326.adbmirror

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingPartsTest {
    private val dir = Files.createTempDirectory("adbmirror-parts").toFile()
    private fun touch(name: String) = File(dir, name).apply { writeText("x") }.absolutePath

    @Test
    fun pickingAnyPartReturnsAllPartsInOrder() {
        val a = touch("adb-mirror_A_20260930_101010.mp4")
        val p2 = touch("adb-mirror_A_20260930_101010_part2.mp4")
        val p3 = touch("adb-mirror_A_20260930_101010_part3.mp4")
        touch("adb-mirror_A_20260930_101010_part5.mp4") // 사이가 빈 번호는 잇지 않는다
        assertEquals(listOf(a, p2, p3), recordingParts(p2))
        assertEquals(listOf(a, p2, p3), recordingParts(a))
    }

    @Test
    fun otherFilesAreReturnedAsIs() {
        val single = touch("clip.mp4")
        assertEquals(listOf(single), recordingParts(single))
        val orphan = touch("x_part2.mp4") // 첫 part가 없으면 그대로
        assertEquals(listOf(orphan), recordingParts(orphan))
    }
}
