package io.github.eoeo0326.adbmirror.core.data.scrcpy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnexBTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun splitsThreeAndFourByteStartCodes() {
        val data = b(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5)
        val nals = AnnexB.split(data)
        assertEquals(3, nals.size)
        assertContentEquals(b(0x67, 1, 2), nals[0])
        assertContentEquals(b(0x68, 3), nals[1])
        assertContentEquals(b(0x65, 4, 5), nals[2])
        assertEquals(listOf(AnnexB.NAL_SPS, AnnexB.NAL_PPS, AnnexB.NAL_IDR), nals.map(AnnexB::nalType))
    }

    @Test
    fun noStartCodeMeansNoNal() {
        assertTrue(AnnexB.split(b(1, 2, 3)).isEmpty())
        assertTrue(AnnexB.split(ByteArray(0)).isEmpty())
    }

    @Test
    fun emptyNalBetweenStartCodesIsDropped() {
        val nals = AnnexB.split(b(0, 0, 1, 0, 0, 1, 0x41, 9))
        assertEquals(1, nals.size)
        assertContentEquals(b(0x41, 9), nals[0])
    }
}
