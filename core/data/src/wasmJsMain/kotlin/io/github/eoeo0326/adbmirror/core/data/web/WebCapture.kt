@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.core.data.web

import io.github.eoeo0326.adbmirror.core.data.recording.Recorder
import io.github.eoeo0326.adbmirror.core.data.recording.RecordingPart
import io.github.eoeo0326.adbmirror.core.data.screenshot.ScreenshotSink
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.js.Promise

@JsFun("(n) => new Uint8Array(n)")
private external fun newBytes(length: Int): JsAny

@JsFun("(a, i, v) => { a[i] = v; }")
private external fun setByteAt(array: JsAny, index: Int, value: Int)

private fun ByteArray.toUint8Array(): JsAny = newBytes(size).also { a -> forEachIndexed { i, b -> setByteAt(a, i, b.toInt() and 0xFF) } }

/** 지금 시각 `yyyyMMdd_HHmmss`(브라우저 시간대). */
@JsFun(
    """() => { const d = new Date(); const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '_' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds()); }""",
)
internal external fun timestamp(): String

@JsFun("(bytes) => navigator.clipboard.write([new ClipboardItem({ 'image/png': new Blob([bytes], { type: 'image/png' }) })])")
private external fun writePngToClipboard(bytes: JsAny): Promise<JsAny?>

@JsFun(
    """(blob, name) => { const url = URL.createObjectURL(blob); const a = document.createElement('a');
  a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 60000); }""",
)
private external fun downloadBlob(blob: JsAny, name: String)

@JsFun("(bytes, type) => new Blob([bytes], { type: type })")
private external fun blobOf(bytes: JsAny, type: String): JsAny

/** 클립보드에는 PNG 이미지로, 저장은 브라우저 다운로드로 내보낸다(저장 폴더 설정은 쓰지 않음). */
object WebScreenshotSink : ScreenshotSink {
    override suspend fun copyToClipboard(png: ByteArray) {
        writePngToClipboard(png.toUint8Array()).await<JsAny?>()
    }

    override suspend fun save(png: ByteArray, dir: String?, baseName: String): String {
        val name = "${baseName}_${timestamp()}.png"
        downloadBlob(blobOf(png.toUint8Array(), "image/png"), name)
        return "다운로드/$name"
    }
}

@JsFun("async () => { const root = await navigator.storage.getDirectory(); return await root.getDirectoryHandle('recordings', { create: true }); }")
private external fun recordingsDir(): Promise<JsAny>

@JsFun("async (dir, name) => (await dir.getFileHandle(name, { create: true })).createWritable()")
private external fun createWritable(dir: JsAny, name: String): Promise<JsAny>

@JsFun("(w, bytes) => w.write(bytes)")
private external fun writeChunk(writable: JsAny, bytes: JsAny): Promise<JsAny?>

@JsFun("(w) => w.close()")
private external fun closeWritable(writable: JsAny): Promise<JsAny?>

@JsFun("async (dir, name) => (await dir.getFileHandle(name)).getFile()")
private external fun fileOf(dir: JsAny, name: String): Promise<JsAny>

/** 앞선 녹화 파일을 지운다(다운로드로 내보낸 뒤라 남겨 둘 이유가 없다). */
@JsFun("async (dir) => { const names = []; for await (const [name] of dir.entries()) names.push(name); for (const n of names) { try { await dir.removeEntry(n); } catch (e) {} } }")
private external fun clearDir(dir: JsAny): Promise<JsAny?>

/**
 * 웹 녹화: 공통 [Recorder]가 만든 fragmented MP4 조각을 OPFS(사이트 전용 파일 공간)에 비동기로 쓰고,
 * 끝나면 브라우저 다운로드로 내보낸다. 메모리에 녹화 전체를 쥐지 않는다. GIF·WebP 변환은 지원하지 않는다.
 */
class WebRecordingRepository(private val scope: CoroutineScope) : RecordingRepository {
    /** 조각 하나. 쓰기는 큐에 넣고 [writer]가 OPFS에 차례로 쓴다. 쓰다 실패하면(용량 초과 등) [failure]에 남기고 더 받지 않는다. */
    private class OpfsPart(override val path: String, dir: JsAny, scope: CoroutineScope) : RecordingPart {
        private val queue = Channel<ByteArray>(Channel.UNLIMITED)
        var failure: Throwable? = null
            private set
        val writer: Job = scope.launch {
            try {
                val w = createWritable(dir, path).await<JsAny>()
                try {
                    for (bytes in queue) writeChunk(w, bytes.toUint8Array()).await<JsAny?>()
                } finally {
                    withContext(NonCancellable) { closeWritable(w).await<JsAny?>() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
                queue.cancel() // 쌓인 조각을 버리고, 뒤이은 write는 큐에 들어가지 않는다
            }
        }

        override fun write(bytes: ByteArray) {
            if (failure == null) queue.trySend(bytes)
        }

        override fun close() {
            queue.close()
        }
    }

    private class Active(val recorder: Recorder, val parts: MutableList<OpfsPart>, val dir: JsAny) {
        lateinit var job: Job
    }

    private val lock = Mutex()
    private val active = mutableMapOf<String, Active>()

    override suspend fun start(session: MirrorSession, outputDir: String?) = lock.withLock {
        check(session.serial !in active) { "이미 녹화 중입니다" }
        val dir = recordingsDir().await<JsAny>()
        if (active.isEmpty()) clearDir(dir).await<JsAny?>()
        val base = "adb-mirror_" + session.serial.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_" + timestamp()
        val parts = mutableListOf<OpfsPart>()
        val recorder = Recorder { index ->
            OpfsPart(if (index == 1) "$base.mp4" else "${base}_part$index.mp4", dir, scope).also { parts += it }
        }
        val self = Active(recorder, parts, dir)
        // UNDISPATCHED: 구독을 등록한 뒤 start가 반환되어, 뒤이은 key frame 요청의 응답을 놓치지 않는다.
        self.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.packets.collect { recorder.accept(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("녹화를 계속하지 못했습니다(${session.serial}): ${e.message}")
            }
        }
        active[session.serial] = self
    }

    override suspend fun stop(serial: String): Recording? = withContext(NonCancellable) {
        val a = lock.withLock { active.remove(serial) } ?: return@withContext null
        a.job.cancelAndJoin()
        val result = a.recorder.finish()
        a.parts.forEach { it.writer.join() }
        // 쓰다 실패한 조각이 있으면 잘린 파일을 정상 녹화처럼 내려받지 않고 알린다.
        a.parts.firstNotNullOfOrNull { it.failure }?.let { throw IllegalStateException("녹화 파일을 쓰지 못했습니다: ${it.message}", it) }
        for (name in result.files) downloadBlob(fileOf(a.dir, name).await<JsAny>(), name)
        Recording(serial, result.files, result.durationMs, result.files.map { "다운로드/$it" })
    }

    override suspend fun info(file: String): VideoInfo = throw UnsupportedOperationException("웹에서는 녹화 파일을 다시 읽지 않습니다")

    override fun supportedFormats(): Set<AnimatedFormat> = emptySet()

    override fun convert(files: List<String>, options: ConversionOptions): Flow<ConversionProgress> =
        flow { throw UnsupportedOperationException("웹에서는 GIF·WebP 변환을 지원하지 않습니다") }
}
