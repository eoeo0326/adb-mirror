@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.core.data.web

import io.github.eoeo0326.adbmirror.core.data.conversion.AnimatedWebpMuxer
import io.github.eoeo0326.adbmirror.core.data.conversion.AnimationConverter
import io.github.eoeo0326.adbmirror.core.data.conversion.RgbaFrame
import io.github.eoeo0326.adbmirror.core.data.recording.Mp4Muxer
import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 헤드리스 Chrome에서 웹 변환을 끝까지 돌린다. 녹화는 실제 scrcpy 스트림의 첫 config·key frame(340×720,
 * fixtures/scrcpy-v4.1-h264-rotate.bin)을 우리 [Mp4Muxer]로 감싼 파일이다. H.264를 디코딩하지 못하는 브라우저면 건너뛴다.
 */
class WebConversionTest {
    /** 같은 key frame을 33ms 간격으로 세 번 넣은 녹화(100ms). */
    private fun recording(): ByteArray {
        val config = EncodedPacket(EncodedPacket.Kind.Config, null, Base64.decode(CONFIG), VideoSize(340, 720))
        val muxer = Mp4Muxer(VideoSize(340, 720), config)
        var out = muxer.header()
        listOf(0L, 33_333L, 66_666L).forEach { pts ->
            muxer.addFrame(EncodedPacket(EncodedPacket.Kind.KeyFrame, pts, Base64.decode(KEY_FRAME)))?.let { out += it }
        }
        muxer.finish()?.let { out += it }
        return out
    }

    private fun source() = recording().let { bytes -> WebVideoFrameSource { bytes.toUint8Array() } }

    @Test
    fun bytesRoundTripThroughWasmMemory() {
        val bytes = ByteArray(1000) { (it * 7).toByte() }
        assertContentEquals(bytes, bytes.toUint8Array().toByteArray())
        val ints = intArrayOf(0, -1, 0x7FFFFFFF, Int.MIN_VALUE, 0x11223344)
        assertContentEquals(ints, ints.toUint8Array().toIntArray())
    }

    @Test
    fun readsInfoAndDecodesScaledFrames() = runTest {
        if (!h264DecodeSupported().await<JsBoolean>().toBoolean()) return@runTest
        val source = source()
        assertEquals(VideoInfo(99, 340, 720), source.info("rec.mp4"))
        val frames = mutableListOf<Pair<Long, RgbaFrame>>()
        source.decode("rec.mp4", 0, 170, 360) { pts, frame -> frames += pts to frame; true }
        assertEquals(listOf(0L, 33L, 66L), frames.map { it.first })
        val first = frames.first().second
        assertEquals(170, first.width)
        assertEquals(360, first.height)
        assertTrue(first.pixels.all { it ushr 24 == 0xFF }, "불투명 ARGB여야 한다")
        assertTrue(first.pixels.distinct().size > 10, "실제 화면이 그려져야 한다")
    }

    @Test
    fun stopsWhenAskedAndStartsFromKeyFrameBeforeStart() = runTest {
        if (!h264DecodeSupported().await<JsBoolean>().toBoolean()) return@runTest
        val seen = mutableListOf<Long>()
        source().decode("rec.mp4", 40, 34, 72) { pts, _ -> seen += pts; false }
        assertEquals(listOf(33L), seen)
    }

    @Test
    fun convertsRecordingToGif() = runTest {
        if (!h264DecodeSupported().await<JsBoolean>().toBoolean()) return@runTest
        val converter = AnimationConverter(source(), CanvasWebpFrameEncoder)
        val gif = converter.convert(listOf("rec.mp4"), ConversionOptions(format = AnimatedFormat.Gif, fps = 10, width = 240)) {}
        assertEquals("GIF89a", gif.copyOfRange(0, 6).decodeToString())
    }

    @Test
    fun canvasEncodesWebpFramesTheMuxerAccepts() = runTest {
        if (!webpEncodingSupported().await<JsBoolean>().toBoolean()) return@runTest
        val frame = RgbaFrame(8, 8, IntArray(64) { if (it % 2 == 0) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() })
        val webp = CanvasWebpFrameEncoder.encode(frame, 80)
        assertEquals("RIFF", webp.copyOfRange(0, 4).decodeToString())
        assertEquals("WEBP", webp.copyOfRange(8, 12).decodeToString())
        val animated = AnimatedWebpMuxer(8, 8).apply { addFrame(webp, 100); addFrame(webp, 100) }.finish()
        assertEquals("WEBP", animated.copyOfRange(8, 12).decodeToString())
    }

    @Test
    fun repositoryConvertsLatestRecordingFromOpfs() = runTest {
        if (!h264DecodeSupported().await<JsBoolean>().toBoolean()) return@runTest
        val repo = WebRecordingRepository(backgroundScope)
        val session = FixtureSession()
        repo.start(session, null)
        session.flow.emit(EncodedPacket(EncodedPacket.Kind.Config, null, Base64.decode(CONFIG), VideoSize(340, 720)))
        listOf(0L, 33_333L, 66_666L).forEach { session.flow.emit(EncodedPacket(EncodedPacket.Kind.KeyFrame, it, Base64.decode(KEY_FRAME))) }
        delay(50)
        val name = repo.stop(session.serial)!!.files.single()

        assertTrue(AnimatedFormat.Gif in repo.supportedFormats())
        assertEquals(VideoInfo(99, 340, 720), repo.info(name))
        val progress = repo.convert(listOf(name), ConversionOptions(format = AnimatedFormat.Gif, fps = 10, width = 240)).toList()
        assertEquals(ConversionProgress.Done("다운로드/" + name.removeSuffix(".mp4") + ".gif"), progress.last())
        assertTrue(progress.any { it is ConversionProgress.Running })
    }

    private class FixtureSession : MirrorSession {
        val flow = MutableSharedFlow<EncodedPacket>()
        override val serial = "web-conversion"
        override val packets: Flow<EncodedPacket> = flow
        override val events: Flow<SessionEvent> = emptyFlow()
        override suspend fun sendTouch(event: TouchEvent) {}
        override suspend fun requestKeyFrame() {}
        override suspend fun stop() {}
    }

    private companion object {
        const val CONFIG = "AAAAAWdCAB/aBYFvn5qCgwMDxYuoAAAAAWjODYg="
        const val KEY_FRAME = "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"
    }
}

@JsFun(
    """() => typeof VideoDecoder === 'undefined' ? Promise.resolve(false)
  : VideoDecoder.isConfigSupported({ codec: 'avc1.42001f' }).then((r) => r.supported).catch(() => false)""",
)
private external fun h264DecodeSupported(): Promise<JsBoolean>
