package io.github.eoeo0326.adbmirror.core.adb.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 순수 Kotlin RSA를 JDK(BigInteger·Signature)와 맞춰 본다. */
class AdbRsaKeyTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pub = pair.public as RSAPublicKey
    private val priv = pair.private as RSAPrivateKey
    private val key = AdbRsaKey(pub.modulus.toByteArray(), priv.privateExponent.toByteArray())

    @Test
    fun signatureVerifiesLikeAdbd() {
        // adbd: RSA_verify(NID_sha1, token, …) = 토큰을 SHA-1 해시로 보고 DigestInfo를 붙여 PKCS#1 v1.5 확인.
        // JDK의 NONEwithRSA는 받은 바이트를 그대로 type 1 패딩하므로 DigestInfo ‖ token을 넣으면 같다.
        repeat(3) {
            val token = Random.nextBytes(20)
            val signature = key.sign(token)
            val verifier = Signature.getInstance("NONEwithRSA").apply {
                initVerify(pub)
                update(SHA1_DIGEST_INFO + token)
            }
            assertTrue(verifier.verify(signature))
        }
    }

    @Test
    fun modPowMatchesBigInteger() {
        val n = pub.modulus
        val words = 64
        val mont = MontgomeryModulus(MontgomeryModulus.fromBytes(n.toByteArray(), words))
        repeat(5) {
            val base = BigInteger(2040, java.util.Random()).mod(n)
            val exp = BigInteger(64, java.util.Random()).add(BigInteger.ONE)
            val ours = mont.modPow(MontgomeryModulus.fromBytes(base.toByteArray(), words), exp.toByteArray())
            assertEquals(base.modPow(exp, n), BigInteger(1, MontgomeryModulus.toBytes(ours, 256)))
        }
    }

    @Test
    fun androidPublicKeyStructMatchesBigInteger() {
        val text = key.androidPublicKey("adb-mirror@test")
        val (b64, name) = text.split(" ")
        assertEquals("adb-mirror@test", name)
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(524, buf.remaining())
        assertEquals(64, buf.int)
        val n0inv = buf.int
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n0inv·n ≡ -1 (mod 2³²)
        assertEquals(r32.subtract(BigInteger.ONE), BigInteger.valueOf(n0inv.toLong() and 0xFFFFFFFFL).multiply(n).mod(r32))
        assertEquals(n, readWords(buf))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), readWords(buf))
        assertEquals(65537, buf.int)
    }

    private fun readWords(buf: ByteBuffer): BigInteger {
        var v = BigInteger.ZERO
        for (i in 0 until 64) v = v.add(BigInteger.valueOf(buf.int.toLong() and 0xFFFFFFFFL).shiftLeft(32 * i))
        return v
    }

    private companion object {
        val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }
}
