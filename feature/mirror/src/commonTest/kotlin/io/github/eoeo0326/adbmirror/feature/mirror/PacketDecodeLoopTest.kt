package io.github.eoeo0326.adbmirror.feature.mirror

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import io.github.eoeo0326.adbmirror.feature.mirror.video.PacketDecodeLoop
import io.github.eoeo0326.adbmirror.feature.mirror.video.PacketDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketDecodeLoopTest {
    private val log = mutableListOf<String>()
    private var accept = true
    private var throwOnDecode = 0
    private var failCreate = 0
    private val errors = mutableListOf<String>()
    private var now = 0L
    private var requests = 0

    private inner class FakeDecoder(override val size: VideoSize) : PacketDecoder {
        override fun decode(packet: EncodedPacket): Boolean {
            if (throwOnDecode > 0) { throwOnDecode--; error("CodecException") }
            log += "${size.width}x${size.height} ${packet.kind}"
            return accept
        }
        override fun close() { log += "close ${size.width}x${size.height}" }
    }

    private val loop = PacketDecodeLoop(
        newDecoder = { size -> if (failCreate > 0) { failCreate--; error("no codec") } else FakeDecoder(size) },
        requestKeyFrame = { requests++ },
        nowMs = { now },
        onError = { errors += it.message ?: "" },
    )
    private val portrait = VideoSize(606, 1280)
    private val landscape = VideoSize(1280, 606)
    private fun config(size: VideoSize?) = EncodedPacket(EncodedPacket.Kind.Config, null, byteArrayOf(0), size)
    private fun key(pts: Long) = EncodedPacket(EncodedPacket.Kind.KeyFrame, pts, byteArrayOf(1))
    private fun frame(pts: Long) = EncodedPacket(EncodedPacket.Kind.Frame, pts, byteArrayOf(2))

    @Test
    fun framesBeforeConfigAreDroppedThenDecoded() {
        loop.start()
        assertEquals(1, requests, "붙을 때 key frame을 요청한다")
        assertFalse(loop.accept(frame(1)))
        loop.accept(config(portrait))
        assertTrue(loop.accept(key(2)))
        assertEquals(listOf("606x1280 Config", "606x1280 KeyFrame"), log)
    }

    @Test
    fun rotationRecreatesDecoderButSameSizeConfigReusesIt() {
        loop.accept(config(portrait))
        loop.accept(key(1))
        loop.accept(config(portrait)) // key frame 재요청에 따른 같은 크기 config
        loop.accept(config(landscape))
        loop.accept(key(2))
        loop.close()
        assertEquals(
            listOf("606x1280 Config", "606x1280 KeyFrame", "606x1280 Config", "close 606x1280", "1280x606 Config", "1280x606 KeyFrame", "close 1280x606"),
            log,
        )
    }

    @Test
    fun droppedFramesRequestKeyFrameAtMostOncePerInterval() {
        loop.start() // t=0 요청 1
        loop.accept(config(portrait))
        accept = false
        now = 200
        repeat(5) { loop.accept(frame(it.toLong())) }
        assertEquals(1, requests, "1초 안의 재요청은 합친다")
        now = 1_100
        loop.accept(frame(9))
        assertEquals(2, requests)
    }

    @Test
    fun decoderErrorRecoversWithNextConfig() {
        loop.start() // t=0 요청 1
        loop.accept(config(portrait))
        throwOnDecode = 1
        now = 2_000
        assertFalse(loop.accept(key(1)))
        assertEquals(listOf("CodecException"), errors)
        assertEquals(listOf("606x1280 Config", "close 606x1280"), log, "오류가 난 디코더는 닫는다")
        assertEquals(2, requests, "key frame을 다시 요청한다")

        assertFalse(loop.accept(frame(2)), "새 config 전 프레임은 버린다")
        loop.accept(config(portrait)) // 요청에 따라 서버가 다시 보낸 config
        assertTrue(loop.accept(key(3)))
        assertEquals("606x1280 KeyFrame", log.last(), "새 디코더로 이어 간다")
    }

    @Test
    fun framesWithoutDecoderKeepAskingForKeyFrameThrottled() {
        now = 5_000
        loop.accept(frame(1))
        now = 5_500
        loop.accept(frame(2))
        now = 6_100
        loop.accept(frame(3))
        assertEquals(2, requests)
    }

    @Test
    fun repeatedCreationFailureGivesUp() {
        failCreate = 10
        repeat(4) {
            now += 2_000
            assertFalse(loop.accept(config(if (it % 2 == 0) portrait else landscape)))
        }
        assertEquals(4, errors.size)
        kotlin.test.assertFailsWith<io.github.eoeo0326.adbmirror.feature.mirror.video.DecoderUnavailableException> {
            loop.accept(config(portrait))
        }
    }
}
