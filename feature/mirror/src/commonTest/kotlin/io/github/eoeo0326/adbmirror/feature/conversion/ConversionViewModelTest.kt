package io.github.eoeo0326.adbmirror.feature.conversion

import io.github.eoeo0326.adbmirror.core.domain.model.AnimatedFormat
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.Recording
import io.github.eoeo0326.adbmirror.core.domain.model.VideoInfo
import io.github.eoeo0326.adbmirror.core.domain.repository.RecordingRepository
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConvertRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetConversionFormatsUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetVideoInfoUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversionViewModelTest {
    private var formats = AnimatedFormat.entries.toSet()
    private var convertedWith: ConversionOptions? = null
    private var convertedFiles: List<String>? = null
    private var conversionGate: CompletableDeferred<Unit>? = null
    private var failConversion: String? = null
    private var failInfo: String? = null
    private var conversionCancelled = false

    private val recordingRepo = object : RecordingRepository {
        override suspend fun start(session: MirrorSession, outputDir: String?) = Unit
        override suspend fun stop(serial: String): Recording? = null
        override suspend fun info(file: String): VideoInfo {
            failInfo?.let { error(it) }
            return VideoInfo(4_000, 340, 720)
        }
        override fun supportedFormats() = formats
        override fun convert(files: List<String>, options: ConversionOptions): Flow<ConversionProgress> = flow {
            convertedWith = options
            convertedFiles = files
            emit(ConversionProgress.Running(0.5f))
            conversionGate?.await()
            failConversion?.let { error(it) }
            emit(ConversionProgress.Done(files.first().removeSuffix(".mp4") + ".gif", uri = "content://media/1"))
        }.onCompletion { cause -> if (cause is kotlinx.coroutines.CancellationException) conversionCancelled = true }
    }

    private fun viewModel() = ConversionViewModel(
        GetVideoInfoUseCase(recordingRepo),
        GetConversionFormatsUseCase(recordingRepo),
        ConvertRecordingUseCase(recordingRepo),
    )

    private fun opened(vararg files: String = arrayOf("/out/a.mp4")) = viewModel().apply { onIntent(ConversionIntent.Open(files.toList())) }

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openLoadsInfoAndClampsWidth() = runTest {
        val draft = opened().state.value.draft!!
        assertEquals(VideoInfo(4_000, 340, 720), draft.info)
        assertEquals(340, draft.options.width) // 기본 480이지만 원본보다 키우지 않는다
        assertEquals(AnimatedFormat.entries.toSet(), draft.formats)
    }

    @Test
    fun unavailableOnPlatformShowsMessageInsteadOfPanel() = runTest {
        formats = emptySet()
        val vm = opened()
        assertNull(vm.state.value.draft)
        assertEquals("이 플랫폼에서는 GIF·WebP 변환을 지원하지 않습니다", vm.state.value.loadError)
        assertEquals(ConversionEffect.ShowMessage("이 플랫폼에서는 GIF·WebP 변환을 지원하지 않습니다"), vm.effects.first())
    }

    @Test
    fun unreadableFileIsReported() = runTest {
        failInfo = "moov 없음"
        val vm = opened()
        assertNull(vm.state.value.draft)
        assertEquals("녹화 파일을 읽지 못했습니다: moov 없음", vm.state.value.loadError)
    }

    @Test
    fun convertReportsProgressAndDone() = runTest {
        val gate = CompletableDeferred<Unit>().also { conversionGate = it }
        val vm = opened()
        vm.onIntent(ConversionIntent.ChangeOptions(vm.state.value.draft!!.options.copy(fps = 15)))
        vm.onIntent(ConversionIntent.Convert)
        assertEquals(ConversionState.Converting(0.5f), vm.state.value.conversion)
        assertEquals(15, convertedWith!!.fps)

        // 변환하는 동안에는 옵션을 바꾸거나 다른 파일을 열지 않는다
        vm.onIntent(ConversionIntent.ChangeOptions(convertedWith!!.copy(fps = 30)))
        assertEquals(15, vm.state.value.draft!!.options.fps)
        vm.onIntent(ConversionIntent.Open(listOf("/out/b.mp4")))
        assertEquals(listOf("/out/a.mp4"), vm.state.value.draft!!.files)

        gate.complete(Unit)
        assertEquals(ConversionState.Done("/out/a.gif", "content://media/1"), vm.state.value.conversion)
        assertEquals(ConversionEffect.Done("/out/a.gif"), vm.effects.first())
        // 옵션을 바꾸면 지난 결과 문구는 사라진다
        vm.onIntent(ConversionIntent.ChangeOptions(convertedWith!!.copy(fps = 5)))
        assertEquals(ConversionState.Idle, vm.state.value.conversion)
    }

    @Test
    fun cancelStopsConversionFlow() = runTest {
        conversionGate = CompletableDeferred()
        val vm = opened()
        vm.onIntent(ConversionIntent.Convert)
        vm.onIntent(ConversionIntent.Cancel)
        assertEquals(ConversionState.Idle, vm.state.value.conversion)
        assertTrue(conversionCancelled)
        assertTrue(vm.state.value.draft != null, "취소해도 화면은 남아 다시 변환할 수 있다")
    }

    @Test
    fun closeWhileConvertingCancelsAndCloses() = runTest {
        conversionGate = CompletableDeferred()
        val vm = opened()
        vm.onIntent(ConversionIntent.Convert)
        vm.onIntent(ConversionIntent.Close)
        assertTrue(conversionCancelled)
        assertNull(vm.state.value.draft)
        assertEquals(ConversionEffect.Closed, vm.effects.first())
    }

    @Test
    fun shutdownCancelsAndWaitsForConversion() = runTest {
        conversionGate = CompletableDeferred()
        val vm = opened()
        vm.onIntent(ConversionIntent.Convert)
        vm.shutdown()
        assertTrue(conversionCancelled)
    }

    @Test
    fun conversionFailureIsShownInPanel() = runTest {
        failConversion = "디코딩 실패"
        val vm = opened()
        vm.onIntent(ConversionIntent.Convert)
        assertEquals(ConversionState.Failed("디코딩 실패"), vm.state.value.conversion)
    }

    @Test
    fun unsupportedFormatBlocksConvert() = runTest {
        formats = setOf(AnimatedFormat.Gif)
        val vm = opened()
        vm.onIntent(ConversionIntent.ChangeOptions(vm.state.value.draft!!.options.copy(format = AnimatedFormat.WebP)))
        assertTrue(vm.state.value.draft!!.problems.isNotEmpty())
        vm.onIntent(ConversionIntent.Convert)
        assertNull(convertedWith)
    }

    @Test
    fun rotatedRecordingPartsAreConvertedTogether() = runTest {
        val vm = opened("/out/a.mp4", "/out/a_part2.mp4")
        assertEquals(8_000, vm.state.value.draft!!.info.durationMs) // part 두 개 길이의 합
        vm.onIntent(ConversionIntent.Convert)
        assertEquals(listOf("/out/a.mp4", "/out/a_part2.mp4"), convertedFiles)
    }

    @Test
    fun progressIsClampedAndTerminates() {
        val s = ConversionUiState()
        fun ConversionUiState.apply(vararg r: ConversionResult) = r.fold(this, ConversionReducer::reduce)
        assertEquals(ConversionState.Converting(1f), s.apply(ConversionResult.Progressed(1.4f)).conversion)
        assertEquals(ConversionState.Converting(0f), s.apply(ConversionResult.Progressed(-1f)).conversion)
        assertEquals(ConversionState.Done("a.gif", null), s.apply(ConversionResult.Finished("a.gif", null)).conversion)
        assertEquals(ConversionState.Idle, s.apply(ConversionResult.Progressed(0.5f), ConversionResult.Cancelled).conversion)
    }

    @Test
    fun sizeFormatting() {
        assertEquals("4.4초", seconds(4_430))
        assertEquals("0.5MB", megabytes(512 * 1024))
        assertEquals("25MB", megabytes(25L * 1024 * 1024))
    }
}
