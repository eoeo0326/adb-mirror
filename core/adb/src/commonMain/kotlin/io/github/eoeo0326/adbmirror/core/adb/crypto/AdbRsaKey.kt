package io.github.eoeo0326.adbmirror.core.adb.crypto

import io.github.eoeo0326.adbmirror.core.adb.protocol.putIntLe
import kotlin.io.encoding.Base64

/**
 * adb 인증용 RSA 키(2048비트). 키 만들기와 저장은 플랫폼이 하고(JVM KeyPairGenerator, 웹 WebCrypto),
 * AUTH 서명과 공개키 형식은 여기서 한다. [modulus]·[privateExponent]는 big-endian 바이트(부호 바이트 없이).
 */
class AdbRsaKey(modulus: ByteArray, privateExponent: ByteArray, val publicExponent: Int = 65537) {
    private val modulusBytes = modulus.dropWhile { it.toInt() == 0 }.toByteArray()
    private val privateExponent = privateExponent.dropWhile { it.toInt() == 0 }.toByteArray()
    private val words = (modulusBytes.size + 3) / 4
    private val mont = MontgomeryModulus(MontgomeryModulus.fromBytes(modulusBytes, words))

    /** 모듈러스 바이트 길이(서명 길이). */
    val size: Int = modulusBytes.size

    init {
        require(size == KEY_BYTES) { "adb는 2048비트 RSA 키만 받습니다(${size * 8}비트)" }
    }

    /**
     * 기기가 보낸 AUTH 토큰(20바이트)에 서명한다. adbd는 토큰을 SHA-1 해시로 보고 PKCS#1 v1.5로 확인하므로
     * `00 01 FF… 00 ‖ SHA-1 DigestInfo ‖ token`을 개인키로 거듭제곱한다(토큰을 다시 해시하지 않음).
     */
    fun sign(token: ByteArray): ByteArray {
        val t = SHA1_DIGEST_INFO.size + token.size
        require(t + 11 <= size) { "토큰이 너무 깁니다" }
        val em = ByteArray(size)
        em[1] = 0x01
        for (i in 2 until size - t - 1) em[i] = 0xFF.toByte()
        SHA1_DIGEST_INFO.copyInto(em, size - t)
        token.copyInto(em, size - token.size)
        val m = MontgomeryModulus.fromBytes(em, words)
        return MontgomeryModulus.toBytes(mont.modPow(m, privateExponent), size)
    }

    /**
     * AUTH RSAPUBLICKEY로 보내는 공개키: Android `RSAPublicKey` 구조체(little-endian)를 base64로 쓰고 ` <이름>`을 붙인다.
     * 구조체: 워드 수, n0inv(-1/n mod 2³²), n(워드), rr(R² mod n), e.
     */
    fun androidPublicKey(name: String): String {
        val n = MontgomeryModulus.fromBytes(modulusBytes, words)
        val struct = ByteArray(4 + 4 + words * 4 + words * 4 + 4)
        var p = 0
        struct.putIntLe(p, words); p += 4
        struct.putIntLe(p, mont.n0inv); p += 4
        for (w in n) { struct.putIntLe(p, w); p += 4 }
        for (w in mont.rSquared) { struct.putIntLe(p, w); p += 4 }
        struct.putIntLe(p, publicExponent)
        return Base64.encode(struct) + " " + name
    }

    private companion object {
        const val KEY_BYTES = 256
        val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
