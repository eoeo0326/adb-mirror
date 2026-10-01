package io.github.eoeo0326.adbmirror.core.adb.protocol

import io.github.eoeo0326.adbmirror.core.adb.EndOfStreamException
import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * TCP로 adbd에 직접 붙는 통로(`adb tcpip <port>`로 켠 기기, 평문 ADB 프로토콜).
 * 웹(WebUSB)과 같은 [AdbConnection]을 JVM에서 실기기로 확인하는 데 쓴다. 무선 디버깅(TLS)은 지원하지 않는다.
 */
class TcpAdbChannel private constructor(private val socket: Socket) : AdbChannel {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = socket.getOutputStream()

    override suspend fun readFully(count: Int): ByteArray = withContext(Dispatchers.IO) {
        ByteArray(count).also {
            try {
                input.readFully(it)
            } catch (_: EOFException) {
                throw EndOfStreamException()
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) { socket.close() }

    companion object {
        suspend fun open(host: String, port: Int, timeoutMs: Int = 5_000): TcpAdbChannel = withContext(Dispatchers.IO) {
            TcpAdbChannel(Socket().apply {
                connect(InetSocketAddress(host, port), timeoutMs)
                tcpNoDelay = true
            })
        }
    }
}

/** [file]에 키(n·d hex 두 줄)가 있으면 읽고, 없으면 만들어 저장한다. 파일은 소유자만 읽게 둔다. */
fun loadOrCreateAdbRsaKey(file: File): AdbRsaKey {
    if (file.isFile) {
        val (n, d) = file.readLines()
        return AdbRsaKey(n.hexToByteArray(), d.hexToByteArray())
    }
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val n = (pair.public as RSAPublicKey).modulus
    val d = (pair.private as RSAPrivateKey).privateExponent
    file.parentFile?.mkdirs()
    file.writeText(n.toString(16).padStart(512, '0') + "\n" + d.toString(16).padStart(512, '0') + "\n")
    file.setReadable(false, false)
    file.setReadable(true, true)
    return AdbRsaKey(n.toByteArray(), d.toByteArray())
}
