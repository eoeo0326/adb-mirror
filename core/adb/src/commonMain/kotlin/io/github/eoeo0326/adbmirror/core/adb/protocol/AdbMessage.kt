package io.github.eoeo0326.adbmirror.core.adb.protocol

/**
 * ADB 와이어 프로토콜 메시지 하나. 24바이트 헤더(little-endian 정수 6개) + payload.
 * 헤더: command, arg0, arg1, payload 길이, payload 체크섬(바이트 합), magic(command xor 0xFFFFFFFF).
 * 체크섬은 프로토콜 버전 0x01000001부터 확인하지 않지만, 예전 adbd를 위해 늘 채운다.
 */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = EMPTY) {
    fun header(): ByteArray = ByteArray(HEADER_SIZE).also {
        it.putIntLe(0, command)
        it.putIntLe(4, arg0)
        it.putIntLe(8, arg1)
        it.putIntLe(12, payload.size)
        it.putIntLe(16, checksum(payload))
        it.putIntLe(20, command xor -1)
    }

    fun encode(): ByteArray = header() + payload

    override fun toString(): String = "${commandName(command)}(${arg0.toUInt()}, ${arg1.toUInt()}, ${payload.size}B)"

    /** 헤더만 읽은 상태. payload는 [length]만큼 따로 읽는다. */
    class Header(val command: Int, val arg0: Int, val arg1: Int, val length: Int, val checksum: Int)

    companion object {
        const val HEADER_SIZE = 24
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257

        /** AUTH arg0 */
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        /** 체크섬을 건너뛰는 버전. */
        const val VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024

        private val EMPTY = ByteArray(0)

        fun decodeHeader(bytes: ByteArray): Header {
            require(bytes.size >= HEADER_SIZE) { "헤더는 ${HEADER_SIZE}바이트입니다" }
            val command = bytes.intLe(0)
            val magic = bytes.intLe(20)
            if (magic != command xor -1) throw AdbProtocolException("헤더 magic 불일치: ${commandName(command)}")
            val length = bytes.intLe(12)
            if (length < 0 || length > MAX_PAYLOAD) throw AdbProtocolException("payload 길이 범위 초과: ${length.toUInt()}")
            return Header(command, bytes.intLe(4), bytes.intLe(8), length, bytes.intLe(16))
        }

        fun checksum(payload: ByteArray): Int = payload.sumOf { it.toInt() and 0xFF }

        fun commandName(command: Int): String =
            (0 until 4).map { ((command ushr (8 * it)) and 0xFF).toChar() }.joinToString("").takeIf { name -> name.all { it in 'A'..'Z' } }
                ?: "0x${command.toUInt().toString(16)}"
    }
}

class AdbProtocolException(message: String) : Exception(message)

internal fun ByteArray.putIntLe(at: Int, v: Int) {
    this[at] = v.toByte()
    this[at + 1] = (v ushr 8).toByte()
    this[at + 2] = (v ushr 16).toByte()
    this[at + 3] = (v ushr 24).toByte()
}

internal fun ByteArray.intLe(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)
