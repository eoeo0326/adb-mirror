package io.github.eoeo0326.adbmirror.core.data.recording

/** 테스트용 MP4 상자 읽기. */
internal class Box(val type: String, val start: Int, val size: Int, val bytes: ByteArray) {
    val bodyStart get() = start + 8
    val end get() = start + size
    fun children(headerExtra: Int = 0): List<Box> = readBoxes(bytes, bodyStart + headerExtra, end)
    fun u32(offset: Int): Long = (0 until 4).fold(0L) { a, i -> (a shl 8) or (bytes[offset + i].toLong() and 0xFF) }
    fun u64(offset: Int): Long = (u32(offset) shl 32) or u32(offset + 4)
}

internal fun readBoxes(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<Box> {
    val boxes = mutableListOf<Box>()
    var p = from
    while (p + 8 <= to) {
        val size = (0 until 4).fold(0L) { a, i -> (a shl 8) or (bytes[p + i].toLong() and 0xFF) }.toInt()
        val type = (4 until 8).map { bytes[p + it].toInt().toChar() }.joinToString("")
        require(size >= 8 && p + size <= to) { "잘못된 상자 $type size=$size at $p" }
        boxes += Box(type, p, size, bytes)
        p += size
    }
    require(p == to) { "상자 뒤에 남는 바이트: ${to - p}" }
    return boxes
}

internal fun List<Box>.find(path: String): Box =
    path.split('/').fold(this) { list, name -> list.first { it.type == name }.let { listOf(it) + childrenOf(it) } }
        .first { it.type == path.substringAfterLast('/') }

private fun childrenOf(box: Box): List<Box> = when (box.type) {
    "moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf", "dinf" -> box.children()
    "stsd" -> box.children(headerExtra = 8)
    else -> emptyList()
}
