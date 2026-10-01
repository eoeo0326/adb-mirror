package io.github.eoeo0326.adbmirror.core.adb.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 고정 벡터로 모든 플랫폼(JVM·Android·wasm)에서 같은 서명이 나오는지 본다.
 * 키는 이 테스트용으로 JDK에서 만든 것이고, 서명은 JDK NONEwithRSA(DigestInfo ‖ token)로 만든 값이다.
 */
class AdbRsaKeyVectorTest {
    @Test
    fun signsFixedTokenLikeJdk() {
        val token = ByteArray(20) { (it * 13 + 7).toByte() }
        val key = AdbRsaKey(hex(AdbRsaKeyVectorKey.N), hex(AdbRsaKeyVectorKey.D))
        assertEquals(AdbRsaKeyVectorKey.S, key.sign(token).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.trimStart('0'))
    }

    private fun hex(s: String): ByteArray {
        val h = if (s.length % 2 == 0) s else "0$s"
        return ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/** 테스트 전용 2048비트 키(JDK로 만든 값, 어떤 기기에도 등록되지 않음). */
object AdbRsaKeyVectorKey {
    const val N =
        "b500d9f75b9bc8459dfca6c9a4455d63e5cecd27d96163beeda7d8d393f38036" +
        "620d84ceedb301a480baf0a9cbfc4378f858bdc82c19876fc44f301b1ae1ae39" +
        "184387ce1ce85360fadd6ebf9040d7549cd6a1d57cae7bb729f81fb4146cb249" +
        "3d0555d38941d6b6b437014698902c1f19ef61ebb1d0918f692ad4bf7e0240d7" +
        "4161b9748909157e8aab68f19326335bacefe56062991b4e2206699429c4fcc2" +
        "b80b883504c910a79e73bf083b91f82ec0d217a023c1b5f0b565370b54f5a968" +
        "aed544240bef673670e8ca1c1c3230d2ef2eb1d2b820fb3ac70020333aaeb4d0" +
        "7ef17106e8579f55556210a63f7ed2993ad83735aaf12ddc718d00d6061e6f01"
    const val D =
        "1ee80ecfe06a18c7de62b23e13192f09ac47641b8e50455e6829356a6744785b" +
        "90e19b10546131021c630f4e070143aa27c9cb5a1f419ab138758712d1c3c062" +
        "8a64998f55bb4be56ee099950736332e84fb5735e36ccdbe2861f053e8fae874" +
        "7e0167b6cfa498dba8a90dc436afc380ea0648939cd0aed22c947e30567d5f01" +
        "b7ee81c0f795acb075f33ac1cfe1564e695709167b7cab514265c33e1bc82466" +
        "89c5f3879720e7a6e85180cfc26f67d106c493502decf8c539fe3db0cbfb4abd" +
        "7fee3be189c6e5c9e28151445fd4077b314ebdd7f7963534ebf158d767df1c72" +
        "f9dde2007e7b747fb7380a1bcd4342c5d6b68da3de65eed049ec25d3d287187b"
    const val S =
        "a5a75790470c7315eb52dd1bc01c5228bb3e8076140f7c603f99599fbcd89a6a" +
        "2257ee2eb148685d0f5476e564ed36ed761a5438694285269281a538cbf9a2ec" +
        "3ebe49ac3be723fe18346fa005dc372d464cb9e89397a07875f03d1457e0d5e4" +
        "5f608a5718fb960ef63dcd634aaa65805778fbd1256f10b340697dd0ab5000b5" +
        "480a1e7a6162f567d8604dca02ef947fe7dca17d929650dff2bf74637c3a205d" +
        "b471f7c1e8c0658484dfaf6c1d7cf200a9a50cc9c9532364560788c569f73951" +
        "3043c2872a1e12c14bbcad2c362da1c4fcb4f398aca069411bef70229cd47141" +
        "c1cfb23adb1abf09e6b868a87c088d895fe666b160fd446cd653bd41ead84f58"
}
