@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.core.data.web

import io.github.eoeo0326.adbmirror.core.domain.model.EncodedPacket
import io.github.eoeo0326.adbmirror.core.domain.model.MirrorSession
import io.github.eoeo0326.adbmirror.core.domain.model.SessionEvent
import io.github.eoeo0326.adbmirror.core.domain.model.TouchEvent
import io.github.eoeo0326.adbmirror.core.domain.model.VideoSize
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 헤드리스 Chrome의 OPFS에 녹화 조각이 실제로 쓰이는지 본다. */
class WebRecordingRepositoryTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val config = EncodedPacket(EncodedPacket.Kind.Config, null, b(0, 0, 0, 1, 0x67, 0x42, 0, 0x1f, 0, 0, 0, 1, 0x68, 0xce), VideoSize(340, 720))

    private class Session : MirrorSession {
        val flow = MutableSharedFlow<EncodedPacket>()
        override val serial = "usb-TEST 1"
        override val packets: Flow<EncodedPacket> = flow
        override val events: Flow<SessionEvent> = emptyFlow()
        override suspend fun sendTouch(event: TouchEvent) {}
        override suspend fun requestKeyFrame() {}
        override suspend fun stop() {}
    }

    @Test
    fun writesFragmentedMp4ToOpfs() = runTest {
        val repo = WebRecordingRepository(backgroundScope)
        val session = Session()
        repo.start(session, null)
        session.flow.emit(config)
        session.flow.emit(EncodedPacket(EncodedPacket.Kind.KeyFrame, 0, b(0, 0, 0, 1, 0x65, 1)))
        session.flow.emit(EncodedPacket(EncodedPacket.Kind.Frame, 500_000, b(0, 0, 0, 1, 0x41, 9)))
        session.flow.emit(EncodedPacket(EncodedPacket.Kind.Frame, 1_000_000, b(0, 0, 0, 1, 0x41, 9)))
        delay(50)
        val recording = repo.stop(session.serial)!!
        val name = recording.files.single()
        assertTrue(name.startsWith("adb-mirror_usb-TEST_1_") && name.endsWith(".mp4"), name)
        assertEquals(listOf("다운로드/$name"), recording.locations)
        val head = opfsHead(name).await<JsString>().toString()
        assertTrue(head.contains("ftyp"), "MP4 머리(ftyp)가 OPFS 파일에 있어야 한다: $head")
        assertTrue(opfsSize(name).await<JsNumber>().toInt() > 100)
    }
}

@JsFun(
    """async (name) => { const d = await (await navigator.storage.getDirectory()).getDirectoryHandle('recordings');
  const f = await (await d.getFileHandle(name)).getFile(); const b = new Uint8Array(await f.slice(0, 16).arrayBuffer());
  return String.fromCharCode(...b); }""",
)
private external fun opfsHead(name: String): Promise<JsString>

@JsFun("async (name) => { const d = await (await navigator.storage.getDirectory()).getDirectoryHandle('recordings'); return (await (await d.getFileHandle(name)).getFile()).size; }")
private external fun opfsSize(name: String): Promise<JsNumber>
