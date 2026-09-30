package io.github.eoeo0326.adbmirror.core.data.conversion

import io.github.eoeo0326.adbmirror.core.adb.ByteArraySource
import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.data.recording.FileRecordingRepository
import io.github.eoeo0326.adbmirror.core.data.recording.Recorder
import io.github.eoeo0326.adbmirror.core.data.recording.RecordingPart
import io.github.eoeo0326.adbmirror.core.data.scrcpy.VideoStreamParser
import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * fixture 첫 세션(세로 340×720, 약 4.4초)을 실제 녹화와 같은 방식으로 MP4로 만든 뒤 FFmpeg로 GIF·WebP로 바꾼다.
 * 결과는 build/conversion/에 남겨 `swift scripts/check_animated_image.swift <file>`로 열어 볼 수 있다.
 */
class FfmpegConversionTest {
    private val fixtures = File(System.getProperty("fixtures.dir")!!)
    private val out = File("build/conversion").apply { mkdirs() }
    private val repo = FileRecordingRepository(CoroutineScope(Dispatchers.Default))

    private suspend fun firstSessionMp4(): File {
        val dir = Files.createTempDirectory("adbmirror-conv").toFile()
        val file = File(dir, "rec.mp4")
        val recorder = Recorder {
            val stream = file.outputStream()
            object : RecordingPart {
                override val path = file.path
                override fun write(bytes: ByteArray) = stream.write(bytes)
                override fun close() = stream.close()
            }
        }
        val parser = VideoStreamParser(ByteArraySource(File(fixtures, "scrcpy-v4.1-h264-rotate.bin").readBytes()))
        parser.readHeader()
        var size: VideoSize? = null
        try {
            loop@ while (true) {
                when (val item = parser.readItem()) {
                    is VideoStreamParser.Item.Session -> if (size == null) size = item.size else break@loop
                    is VideoStreamParser.Item.Packet -> item.packet.let { p ->
                        recorder.accept(if (p.kind == EncodedPacket.Kind.Config) EncodedPacket(p.kind, null, p.data, size) else p)
                    }
                }
            }
        } catch (_: EndOfStreamException) {
        }
        recorder.finish()
        return file
    }

    @Test
    fun infoReadsDurationAndSizeOfFragmentedMp4() = runBlocking<Unit> {
        val info = repo.info(firstSessionMp4().path)
        assertEquals(340, info.width)
        assertEquals(720, info.height)
        assertTrue(info.durationMs in 4_000..4_600, "길이 ${info.durationMs}ms")
    }

    @Test
    fun convertsToGifThatImageIoReads() = runBlocking<Unit> {
        val mp4 = firstSessionMp4()
        val progress = repo.convert(listOf(mp4.path), ConversionOptions(fps = 10, width = 240, endMs = 2_000)).toList()
        val done = assertIs<ConversionProgress.Done>(progress.last())
        assertTrue(progress.filterIsInstance<ConversionProgress.Running>().isNotEmpty())
        val gif = File(done.file)
        assertEquals(File(mp4.parentFile, "rec.gif"), gif)
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(gif).use { input ->
            reader.input = input
            val frames = reader.getNumImages(true)
            assertTrue(frames in 2..20, "프레임 $frames")
            val first = reader.read(0)
            assertEquals(240, first.width)
            assertEquals(508, first.height) // 720 * 240 / 340
        }
        gif.copyTo(File(out, "fixture.gif"), overwrite = true)
    }

    @Test
    fun convertsToAnimatedWebp() = runBlocking<Unit> {
        val mp4 = firstSessionMp4()
        val done = repo.convert(listOf(mp4.path), ConversionOptions(format = AnimatedFormat.WebP, fps = 10, width = 240, endMs = 2_000)).toList().last()
        val webp = File(assertIs<ConversionProgress.Done>(done).file).readBytes()
        assertEquals("RIFF", webp.decodeToString(0, 4))
        assertEquals("WEBP", webp.decodeToString(8, 12))
        assertEquals("VP8X", webp.decodeToString(12, 16))
        val text = webp.decodeToString()
        assertTrue(text.contains("ANIM"))
        val frames = Regex("ANMF").findAll(text).count()
        assertTrue(frames in 2..20, "프레임 $frames")
        File(out, "fixture.webp").writeBytes(webp)
    }

    @Test
    fun secondConversionDoesNotOverwriteAndCancelLeavesNoFile() = runBlocking<Unit> {
        val mp4 = firstSessionMp4()
        repo.convert(listOf(mp4.path), ConversionOptions(fps = 5, width = 240, endMs = 1_000)).toList()
        val second = repo.convert(listOf(mp4.path), ConversionOptions(fps = 5, width = 240, endMs = 1_000)).toList().last()
        assertEquals("rec_2.gif", File((second as ConversionProgress.Done).file).name)

        // 첫 진행률을 받자마자 취소(first)하면 쓰던 파일이 남지 않는다.
        repo.convert(listOf(mp4.path), ConversionOptions(fps = 30, width = 480)).first()
        assertEquals(setOf("rec.mp4", "rec.gif", "rec_2.gif"), mp4.parentFile.list()!!.toSet())
    }

    /** fixture 전체(세로 → 가로 → 세로)를 녹화처럼 part 3개로 나눈다. */
    private suspend fun allPartsMp4(): List<File> {
        val dir = Files.createTempDirectory("adbmirror-parts").toFile()
        val recorder = Recorder { i ->
            val file = File(dir, if (i == 1) "rec.mp4" else "rec_part$i.mp4")
            val stream = file.outputStream()
            object : RecordingPart {
                override val path = file.path
                override fun write(bytes: ByteArray) = stream.write(bytes)
                override fun close() = stream.close()
            }
        }
        val parser = VideoStreamParser(ByteArraySource(File(fixtures, "scrcpy-v4.1-h264-rotate.bin").readBytes()))
        parser.readHeader()
        var size: VideoSize? = null
        try {
            while (true) {
                when (val item = parser.readItem()) {
                    is VideoStreamParser.Item.Session -> size = item.size
                    is VideoStreamParser.Item.Packet -> item.packet.let { p ->
                        recorder.accept(if (p.kind == EncodedPacket.Kind.Config) EncodedPacket(p.kind, null, p.data, size) else p)
                    }
                }
            }
        } catch (_: EndOfStreamException) {
        }
        return recorder.finish().files.map(::File)
    }

    /**
     * part 3개를 이어 한 GIF로 만든다. 캔버스는 첫 part(세로)를 240px로 줄인 크기이고,
     * 가운데 가로 part는 위아래가 검정인 채로 들어간다.
     */
    @Test
    fun joinsRotatedPartsWithLetterbox() = runBlocking<Unit> {
        val files = allPartsMp4()
        assertEquals(3, files.size)
        val infos = files.map { repo.info(it.path) }
        val done = repo.convert(files.map { it.path }, ConversionOptions(fps = 10, width = 240)).toList().last()
        val gif = File(assertIs<ConversionProgress.Done>(done).file)
        assertEquals("rec.gif", gif.name)

        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(gif).use { input ->
            reader.input = input
            val n = reader.getNumImages(true)
            var canvas: java.awt.image.BufferedImage? = null
            var tMs = 0L
            var totalMs = 0L
            val landscapeFrom = infos[0].durationMs
            val landscapeTo = landscapeFrom + infos[1].durationMs
            var checkedLandscape = false
            for (i in 0 until n) {
                val img = reader.read(i)
                val meta = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0")
                val nodes = (0 until meta.childNodes.length).map { meta.childNodes.item(it) }
                val desc = nodes.first { it.nodeName == "ImageDescriptor" }
                val gce = nodes.first { it.nodeName == "GraphicControlExtension" }
                if (canvas == null) {
                    canvas = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    assertEquals(240 to 508, img.width to img.height)
                }
                val left = desc.attributes.getNamedItem("imageLeftPosition").nodeValue.toInt()
                val top = desc.attributes.getNamedItem("imageTopPosition").nodeValue.toInt()
                canvas.graphics.drawImage(img, left, top, null)
                val delay = gce.attributes.getNamedItem("delayTime").nodeValue.toLong() * 10
                // 가로 part 한가운데 시각의 화면: 위쪽 가장자리는 검정, 가운데는 화면 내용
                val mid = (landscapeFrom + landscapeTo) / 2
                if (!checkedLandscape && tMs <= mid && mid < tMs + delay) {
                    assertEquals(0x000000, canvas.getRGB(120, 10) and 0xFFFFFF, "레터박스(위쪽) 검정")
                    assertTrue((canvas.getRGB(120, 254) and 0xFFFFFF) != 0, "가운데는 화면 내용")
                    checkedLandscape = true
                }
                tMs += delay
                totalMs += delay
            }
            assertTrue(checkedLandscape, "가로 part 구간의 프레임을 찾지 못했다")
            val expected = infos.sumOf { it.durationMs }
            assertTrue(kotlin.math.abs(totalMs - expected) <= 150, "전체 길이 $totalMs ms (기대 $expected)")
        }
        gif.copyTo(File(out, "fixture-joined.gif"), overwrite = true)
    }
}
