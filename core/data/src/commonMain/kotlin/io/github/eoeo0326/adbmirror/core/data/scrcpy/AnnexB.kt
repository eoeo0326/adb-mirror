package io.github.eoeo0326.adbmirror.core.data.scrcpy

/** H.264 Annex B 바이트열(start code로 구분) 다루기. */
object AnnexB {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** `00 00 01` / `00 00 00 01` start code 기준으로 NAL 본문만 잘라낸다. */
    fun split(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<IntArray>() // [start code 시작, NAL 시작]
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ONE) {
                val codeStart = if (i > 0 && data[i - 1] == ZERO) i - 1 else i
                starts += intArrayOf(codeStart, i + 3)
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexedNotNull { index, (_, nalStart) ->
            val end = if (index + 1 < starts.size) starts[index + 1][0] else data.size
            if (end > nalStart) data.copyOfRange(nalStart, end) else null
        }
    }

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) 0 else nal[0].toInt() and 0x1F

    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
}
