package io.github.eoeo0326.adbmirror.core.data.recording

import io.github.eoeo0326.adbmirror.core.data.conversion.AnimationConverter
import io.github.eoeo0326.adbmirror.core.data.conversion.FfmpegVideoFrameSource
import io.github.eoeo0326.adbmirror.core.data.conversion.FfmpegWebpFrameEncoder
import io.github.eoeo0326.adbmirror.core.data.screenshot.defaultOutputDir
import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.Recording
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import io.github.eoeo0326.adbmirror.core.domain.repository.RecordingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 기기마다 녹화 하나를 [scope]에서 돌리며 파일로 쓴다.
 * 이름은 `adb-mirror_<serial>_<yyyyMMdd_HHmmss>.mp4`, 회전으로 나뉘면 `_part2`, `_part3` …을 붙인다.
 */
class FileRecordingRepository(
    private val scope: CoroutineScope,
    private val home: File = File(System.getProperty("user.home")),
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val converter: AnimationConverter = AnimationConverter(FfmpegVideoFrameSource(), FfmpegWebpFrameEncoder.createOrNull()),
) : RecordingRepository {
    private class Active(val recorder: Recorder, val job: Job)

    private val lock = Mutex()
    private val active = mutableMapOf<String, Active>()

    override suspend fun start(session: MirrorSession, outputDir: String?) = lock.withLock {
        check(session.serial !in active) { "이미 녹화 중입니다" }
        val dir = (outputDir?.let(::File) ?: defaultOutputDir(home)).also { it.mkdirs() }
        val base = "adb-mirror_" + session.serial.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_" + now().format(STAMP)
        val recorder = Recorder { index -> FilePart(uniqueFile(dir, base, index)) }
        // UNDISPATCHED: collect가 구독을 등록한 뒤에야 start가 반환되므로, 뒤이은 key frame 요청의 응답을 놓치지 않는다.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                session.packets.collect { recorder.accept(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 쓰기 실패(디스크 가득 참 등): 더 쓰지 않고, stop()이 그때까지 쓴 파일을 닫아 돌려준다.
                System.err.println("녹화를 계속하지 못했습니다(${session.serial}): ${e.message}")
            }
        }
        active[session.serial] = Active(recorder, job)
    }

    override suspend fun stop(serial: String): Recording? = withContext(NonCancellable) {
        val a = lock.withLock { active.remove(serial) } ?: return@withContext null
        a.job.cancelAndJoin() // 이 뒤로는 recorder를 만지는 코루틴이 없다
        val result = withContext(Dispatchers.IO) { a.recorder.finish() }
        Recording(serial, result.files, result.durationMs)
    }

    override suspend fun info(file: String): VideoInfo = converter.info(file)

    override fun supportedFormats(): Set<AnimatedFormat> = converter.supportedFormats

    /** 원본 옆 `<이름>.gif|webp`(있으면 `_2`…)로 쓴다. 끝날 때까지 임시 `.part` 파일에 쓰고, 취소·실패하면 지운다. */
    override fun convert(file: String, options: ConversionOptions): Flow<ConversionProgress> = flow {
        val source = File(file)
        val ext = if (options.format == AnimatedFormat.Gif) "gif" else "webp"
        val base = source.name.substringBeforeLast('.')
        val dir = source.absoluteFile.parentFile
        val target = generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) "$base.$ext" else "${base}_$n.$ext") }
            .first { !it.exists() }
        // 취소 직후 다시 변환해도 이전 흐름의 임시 파일과 겹치지 않게 이름을 따로 받는다.
        val tmp = File.createTempFile(target.name + ".", ".part", dir)
        try {
            val bytes = converter.convert(file, options) { emit(ConversionProgress.Running(it)) }
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath())
            emit(ConversionProgress.Done(target.absolutePath))
        } finally {
            tmp.delete()
        }
    }.flowOn(Dispatchers.Default)

    private fun uniqueFile(dir: File, base: String, index: Int): File {
        val name = if (index == 1) base else "${base}_part$index"
        return generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) "$name.mp4" else "${name}_$n.mp4") }
            .first { !it.exists() }
    }

    private class FilePart(file: File) : RecordingPart {
        override val path: String = file.absolutePath
        // 조각마다 바로 쓰므로(버퍼 없음) 앱이 죽어도 이미 쓴 조각은 남는다.
        private val out = FileOutputStream(file)
        override fun write(bytes: ByteArray) = out.write(bytes)
        override fun close() = out.close()
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
