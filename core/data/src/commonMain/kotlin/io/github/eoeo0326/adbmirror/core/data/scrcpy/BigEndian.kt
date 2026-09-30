package io.github.eoeo0326.adbmirror.core.data.scrcpy

internal fun ByteArray.u32(offset: Int): Long =
    (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (this[offset + i].toLong() and 0xFF) }

internal fun ByteArray.u64(offset: Int): ULong =
    (0 until 8).fold(0UL) { acc, i -> (acc shl 8) or (this[offset + i].toULong() and 0xFFUL) }
