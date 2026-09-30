package io.github.eoeo0326.adbmirror.core.data.scrcpy

import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 기기에서 캡처한 fixture를 끝까지 파싱해 capture_fixture.py의 참조 파서 결과와 비교한다. */
class FixtureParseTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: error("fixtures.dir 시스템 속성이 없습니다"))
    private val name = "scrcpy-v4.1-h264-rotate"
    private val expected = File(dir, "$name.json").readText()

    private fun number(key: String): Long =
        Regex("\"$key\": (\\d+)").find(expected)?.groupValues?.get(1)?.toLong() ?: error("$key 없음")

    private data class Session(val width: Int, val height: Int, val frameIndex: Int)

    private fun expectedSessions(): List<Session> =
        Regex("\"width\": (\\d+),\\s*\"height\": (\\d+),\\s*\"frameIndex\": (\\d+)").findAll(expected)
            .map { m -> m.groupValues.drop(1).map(String::toInt).let { Session(it[0], it[1], it[2]) } }.toList()

    @Test
    fun matchesReferenceParser() = runTest {
        val source = ByteArraySource(File(dir, "$name.bin").readBytes())
        val parser = VideoStreamParser(source)
        val header = parser.readHeader()

        val sessions = mutableListOf<Session>()
        val packets = mutableListOf<EncodedPacket>()
        try {
            while (true) {
                when (val item = parser.readItem()) {
                    is VideoStreamParser.Item.Session ->
                        sessions += Session(item.size.width, item.size.height, packets.count { it.kind != EncodedPacket.Kind.Config })
                    is VideoStreamParser.Item.Packet -> packets += item.packet
                }
            }
        } catch (_: EndOfStreamException) {
        }

        val frames = packets.filter { it.kind != EncodedPacket.Kind.Config }
        assertEquals(number("deviceNameLength").toInt(), header.deviceName.length)
        assertEquals(VideoStreamParser.CODEC_H264, header.codecId)
        assertEquals(expectedSessions(), sessions)
        assertEquals(number("configPackets").toInt(), packets.count { it.kind == EncodedPacket.Kind.Config })
        assertEquals(number("framePackets").toInt(), frames.size)
        assertEquals(number("keyFrames").toInt(), frames.count { it.kind == EncodedPacket.Kind.KeyFrame })
        assertEquals(number("payloadBytes"), packets.sumOf { it.data.size.toLong() })
        assertEquals(number("firstPtsUs"), frames.first().ptsUs)
        assertEquals(number("lastPtsUs"), frames.last().ptsUs)
        assertEquals(number("trailingBytes").toInt(), source.remaining)
    }

    @Test
    fun configCarriesSpsPpsAndKeyFramesCarryIdr() = runTest {
        val parser = VideoStreamParser(ByteArraySource(File(dir, "$name.bin").readBytes()))
        parser.readHeader()
        val packets = mutableListOf<EncodedPacket>()
        try {
            while (true) (parser.readItem() as? VideoStreamParser.Item.Packet)?.let { packets += it.packet }
        } catch (_: EndOfStreamException) {
        }
        packets.filter { it.kind == EncodedPacket.Kind.Config }.forEach { config ->
            val types = AnnexB.split(config.data).map(AnnexB::nalType)
            assertTrue(AnnexB.NAL_SPS in types && AnnexB.NAL_PPS in types, "config NAL 종류: $types")
        }
        packets.filter { it.kind == EncodedPacket.Kind.KeyFrame }.forEach { key ->
            assertTrue(AnnexB.NAL_IDR in AnnexB.split(key.data).map(AnnexB::nalType))
        }
    }
}
