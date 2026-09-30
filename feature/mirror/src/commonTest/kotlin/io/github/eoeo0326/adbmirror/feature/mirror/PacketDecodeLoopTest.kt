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
    private var now = 0L
    private var requests = 0

    private inner class FakeDecoder(override val size: VideoSize) : PacketDecoder {
        override fun decode(packet: EncodedPacket): Boolean {
            log += "${size.width}x${size.height} ${packet.kind}"
            return accept
        }
        override fun close() { log += "close ${size.width}x${size.height}" }
    }

    private val loop = PacketDecodeLoop(::FakeDecoder, requestKeyFrame = { requests++ }, nowMs = { now })
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
}
