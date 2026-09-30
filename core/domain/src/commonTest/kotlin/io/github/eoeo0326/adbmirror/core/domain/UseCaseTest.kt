package io.github.eoeo0326.adbmirror.core.domain

import io.github.eoeo0326.adbmirror.core.domain.model.ConversionOptions
import io.github.eoeo0326.adbmirror.core.domain.model.ConversionProgress
import io.github.eoeo0326.adbmirror.core.domain.model.Device
import io.github.eoeo0326.adbmirror.core.domain.model.DeviceState
import io.github.eoeo0326.adbmirror.core.domain.model.Settings
import io.github.eoeo0326.adbmirror.core.domain.model.TouchAction
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import io.github.eoeo0326.adbmirror.core.domain.usecase.ConvertRecordingUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.DeviceNotSelectableException
import io.github.eoeo0326.adbmirror.core.domain.usecase.GetDevicesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SendTouchUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.SetShowTouchesUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartMirroringUseCase
import io.github.eoeo0326.adbmirror.core.domain.usecase.StartRecordingUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UseCaseTest {
    private val size = VideoSize(340, 720)

    @Test
    fun devicesAreSortedOnlineFirstThenBySerial() = runTest {
        val repo = FakeDeviceRepository(
            listOf(
                Device("C", DeviceState.Offline),
                Device("B", DeviceState.Online),
                Device("D", DeviceState.Unauthorized),
                Device("A", DeviceState.Online),
            ),
        )
        val serials = GetDevicesUseCase(repo)().first().map { it.serial }
        assertEquals(listOf("A", "B", "C", "D"), serials)
    }

    @Test
    fun startMirroringRejectsNonOnlineDevice() = runTest {
        val useCase = StartMirroringUseCase(FakeMirrorRepository(), FakeSettingsRepository())
        assertFailsWith<DeviceNotSelectableException> {
            useCase(Device("X", DeviceState.Unauthorized))
        }
    }

    @Test
    fun startMirroringUsesSettingsAndAlwaysOpensControl() = runTest {
        val mirror = FakeMirrorRepository()
        val settings = FakeSettingsRepository(Settings(maxSize = 720, maxFps = 30, viewOnly = true))
        StartMirroringUseCase(mirror, settings)(Device("S1", DeviceState.Online))
        val (serial, options) = mirror.lastStart!!
        assertEquals("S1", serial)
        assertEquals(720, options.maxSize)
        assertEquals(30, options.maxFps)
        assertTrue(options.control)
    }

    @Test
    fun sendTouchClampsToVideoBounds() = runTest {
        val session = FakeSession()
        val useCase = SendTouchUseCase(FakeSettingsRepository())
        useCase(session, TouchEvent(TouchAction.Move, -5, 900, size))
        useCase(session, TouchEvent(TouchAction.Up, 500, 10, size))
        assertEquals(listOf(0 to 719, 339 to 10), session.touches.map { it.x to it.y })
    }

    @Test
    fun sendTouchDropsEventsInViewOnly() = runTest {
        val session = FakeSession()
        SendTouchUseCase(FakeSettingsRepository(Settings(viewOnly = true)))(
            session,
            TouchEvent(TouchAction.Down, 1, 1, size),
        )
        assertTrue(session.touches.isEmpty())
    }

    @Test
    fun startRecordingSubscribesBeforeRequestingKeyFrame() = runTest {
        val session = FakeSession()
        val recordings = FakeRecordingRepository(session)
        StartRecordingUseCase(recordings, FakeSettingsRepository(Settings(outputDir = "/out")))(session)
        assertEquals(listOf("recordStart", "keyFrame"), session.log)
        assertEquals("/out", recordings.startedDir)
    }

    @Test
    fun convertRejectsInvalidOptions() {
        val useCase = ConvertRecordingUseCase(FakeRecordingRepository(FakeSession()))
        val error = assertFailsWith<IllegalArgumentException> {
            useCase("a.mp4", ConversionOptions(fps = 60, startMs = 500, endMs = 100))
        }
        assertTrue("fps" in error.message!!)
        assertTrue("끝 시각" in error.message!!)
    }

    @Test
    fun convertPassesValidOptions() = runTest {
        val useCase = ConvertRecordingUseCase(FakeRecordingRepository(FakeSession()))
        val result = useCase("a.mp4", ConversionOptions()).toList()
        assertEquals(listOf(ConversionProgress.Done("a.mp4.gif")), result)
    }

    @Test
    fun setShowTouchesReturnsPreviousValue() = runTest {
        val repo = FakeDeviceRepository(emptyList()).apply { currentShowTouches = true }
        val useCase = SetShowTouchesUseCase(repo)
        val previous = useCase("S1", false)
        useCase("S1", previous)
        assertEquals(listOf("S1" to false, "S1" to true), repo.showTouchesCalls)
    }
}
