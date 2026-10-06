package io.github.eoeo0326.adbmirror.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun daemonNoticeLinesAreSkipped() {
        val body = "R3CM90LKDDJ\tdevice\n"
        val msg = body.length.toString(16).padStart(4, '0') + body
        val buffer = StringBuilder("* daemon not running; starting now at tcp:5037\n* daemon started successfully\n" + msg + "* dae")
        assertEquals(listOf(body), AdbOutputParser.takeTrackMessages(buffer))
        assertEquals("* dae", buffer.toString()) // 덜 온 안내 줄은 남긴다
    }
}
