package io.github.eoeo0326.adbmirror.core.adb.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실기기 확인: `adb tcpip 5555` 뒤 `-Padbmirror.tcp=<ip>:5555`로 돌린다(없으면 건너뜀).
 * 처음에는 기기에 "USB 디버깅을 허용하시겠습니까?"가 뜬다. 키는 `-Padbmirror.tcpKey`(기본 build/) 파일에 둔다.
 */
class AdbConnectionDeviceTest {
    private val target = System.getProperty("adbmirror.tcp").orEmpty()

    @Test
    fun shellAndPushOnRealDevice() = runBlocking {
        if (target.isEmpty()) return@runBlocking
        val (host, port) = target.split(':').let { it[0] to it[1].toInt() }
        val key = loadOrCreateAdbRsaKey(File(System.getProperty("adbmirror.tcpKey")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = withTimeout(90_000) {
                AdbConnection.connect(TcpAdbChannel.open(host, port), key, "adb-mirror-test@jvm", scope) {
                    println("기기에서 디버깅 허용을 눌러 주세요")
                }
            }
            println("banner: ${connection.banner}")
            assertTrue(connection.model.orEmpty().isNotEmpty())
            assertEquals(connection.model, connection.shell("getprop ro.product.model").trim())

            val data = Random(1).nextBytes(3 * 1024 * 1024 + 123) // 64KiB DATA 조각·maxdata 경계를 여러 번 넘는다
            val path = "/data/local/tmp/adbmirror-protocol-test.bin"
            connection.push(data, path)
            val md5 = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
            assertEquals(md5, connection.shell("md5sum $path").substringBefore(' ').trim())
            connection.shell("rm $path")
            connection.close()
        } finally {
            scope.cancel()
        }
    }
}
