package io.github.eoeo0326.adbmirror.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbOutputParserTest {
    @Test
    fun parsesDevicesLongFormat() {
        val text = """
            * daemon started successfully
            List of devices attached
            R3CM90LKDDJ            device usb:1-1 product:d1xks model:SM_N976N device:d1x transport_id:3
            emulator-5554          unauthorized transport_id:4
            192.168.0.9:5555       offline
        """.trimIndent()
        assertEquals(
            listOf(
                AdbDevice("R3CM90LKDDJ", "device", "SM N976N"),
                AdbDevice("emulator-5554", "unauthorized", null),
                AdbDevice("192.168.0.9:5555", "offline", null),
            ),
            AdbOutputParser.parseDevices(text),
        )
    }

    @Test
    fun trackMessagesAreSplitAndPartialKept() {
        val body = "R3CM90LKDDJ\tdevice\n"
        val msg = body.length.toString(16).padStart(4, '0') + body
        val buffer = StringBuilder(msg + "0000" + msg.take(6))
        val messages = AdbOutputParser.takeTrackMessages(buffer)
        assertEquals(listOf(body, ""), messages)
        assertEquals(msg.take(6), buffer.toString())
        assertTrue(AdbOutputParser.parseDevices(messages[1]).isEmpty())
    }

    @Test
    fun pairSucceedsOnlyWithSuccessLine() {
        assertNull(AdbOutputParser.pairFailure("Successfully paired to 192.168.0.9:37099 [guid=adb-R3CM90LKDDJ-ab12cd]"))
        assertEquals(
            "페어링하지 못했습니다: Failed: Wrong password or connection was dropped.",
            AdbOutputParser.pairFailure("Failed: Wrong password or connection was dropped.\n"),
        )
        assertEquals("페어링하지 못했습니다", AdbOutputParser.pairFailure(""))
    }

    @Test
    fun connectFailureIsDetectedEvenWithExitZero() {
        assertNull(AdbOutputParser.connectFailure("connected to 192.168.0.9:41234"))
        assertNull(AdbOutputParser.connectFailure("already connected to 192.168.0.9:41234"))
        assertEquals(
            "연결하지 못했습니다: failed to connect to '192.168.0.9:41234': Connection refused",
            AdbOutputParser.connectFailure("failed to connect to '192.168.0.9:41234': Connection refused"),
        )
        assertEquals(
            "연결하지 못했습니다: failed to authenticate to 192.168.0.9:41234",
            AdbOutputParser.connectFailure("failed to authenticate to 192.168.0.9:41234\n"),
        )
        assertEquals(
            "연결하지 못했습니다: cannot connect to 192.168.0.9:1: Connection refused (61)",
            AdbOutputParser.connectFailure("cannot connect to 192.168.0.9:1: Connection refused (61)"),
        )
    }
}
