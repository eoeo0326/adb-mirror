package io.github.eoeo0326.adbmirror.feature.mirror.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvcCodecStringTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun readsProfileConstraintLevelFromFirstSps() {
        // 4바이트 시작 코드 + SPS(High 4.2), 이어서 PPS
        val config = b(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x2a, 0xac, 0, 0, 0, 1, 0x68, 0xee, 0x3c, 0x80)
        assertEquals("avc1.64002a", avcCodecString(config))
        // 3바이트 시작 코드, Baseline 3.1(constraint 0xc0)
        assertEquals("avc1.42c01f", avcCodecString(b(0, 0, 1, 0x67, 0x42, 0xc0, 0x1f, 0x95)))
    }

    @Test
    fun nullWithoutSps() {
        assertNull(avcCodecString(b(0, 0, 0, 1, 0x68, 0xee, 0x3c, 0x80)))
    }
}
