package io.github.eoeo0326.adbmirror.core.adb.crypto

/**
 * 홀수 모듈러스 n에 대한 Montgomery 곱셈(CIOS). 웹 WebCrypto는 이미 해시된 값을 그대로 RSA 서명할 수 없어,
 * ADB AUTH 서명을 모든 플랫폼에서 같은 순수 Kotlin 코드로 한다. 수는 32비트 limb의 little-endian [IntArray]다.
 * 나눗셈이 필요 없도록 R² mod n도 2배를 거듭해 구한다.
 */
internal class MontgomeryModulus(private val n: IntArray) {
    val size = n.size

    init {
        require(size > 0 && n[0] and 1 == 1) { "모듈러스는 홀수여야 합니다" }
        require(n[size - 1] != 0) { "모듈러스 최상위 limb가 0입니다" }
    }

    /** -n⁻¹ mod 2³² */
    val n0inv: Int = -inverse32(n[0])

    /** R² mod n, R = 2^(32·size). Android 공개키의 rr 값이기도 하다. */
    val rSquared: IntArray = run {
        var x = IntArray(size).also { it[0] = 1 }
        repeat(2 * 32 * size) { x = doubleMod(x) }
        x
    }

    /** a·b·R⁻¹ mod n. a, b < n. */
    fun multiply(a: IntArray, b: IntArray): IntArray {
        val t = LongArray(size + 2)
        for (i in 0 until size) {
            var carry = 0L
            val ai = a[i].toLong() and MASK
            for (j in 0 until size) {
                val s = t[j] + ai * (b[j].toLong() and MASK) + carry
                t[j] = s and MASK
                carry = s ushr 32
            }
            var s = t[size] + carry
            t[size] = s and MASK
            t[size + 1] = s ushr 32

            val m = (t[0] * (n0inv.toLong() and MASK)) and MASK
            s = t[0] + m * (n[0].toLong() and MASK)
            carry = s ushr 32
            for (j in 1 until size) {
                s = t[j] + m * (n[j].toLong() and MASK) + carry
                t[j - 1] = s and MASK
                carry = s ushr 32
            }
            s = t[size] + carry
            t[size - 1] = s and MASK
            t[size] = t[size + 1] + (s ushr 32)
            t[size + 1] = 0
        }
        val result = IntArray(size) { t[it].toInt() }
        return if (t[size] != 0L || compare(result, n) >= 0) subtract(result, n) else result
    }

    /** base^exp mod n. base < n, exp는 big-endian 바이트. */
    fun modPow(base: IntArray, exponent: ByteArray): IntArray {
        val one = IntArray(size).also { it[0] = 1 }
        val baseM = multiply(base, rSquared)
        var acc = multiply(one, rSquared) // 1의 Montgomery 형식
        for (byte in exponent) {
            for (bit in 7 downTo 0) {
                acc = multiply(acc, acc)
                if ((byte.toInt() ushr bit) and 1 == 1) acc = multiply(acc, baseM)
            }
        }
        return multiply(acc, one)
    }

    private fun doubleMod(x: IntArray): IntArray {
        val out = IntArray(size)
        var carry = 0
        for (i in 0 until size) {
            out[i] = (x[i] shl 1) or carry
            carry = x[i] ushr 31
        }
        return if (carry != 0 || compare(out, n) >= 0) subtract(out, n) else out
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL

        /** 홀수 x의 2³² 역원(Newton: 한 번에 맞는 비트가 두 배). */
        fun inverse32(x: Int): Int {
            var inv = x // x·x ≡ 1 (mod 8)
            repeat(4) { inv *= 2 - x * inv }
            return inv
        }

        fun compare(a: IntArray, b: IntArray): Int {
            for (i in a.indices.reversed()) {
                val c = a[i].toUInt().compareTo(b[i].toUInt())
                if (c != 0) return c
            }
            return 0
        }

        /** a - b (같은 길이, 넘친 자리는 버림 = mod 2^(32·size)). */
        fun subtract(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0L
            for (i in a.indices) {
                val d = (a[i].toLong() and MASK) - (b[i].toLong() and MASK) - borrow
                out[i] = d.toInt()
                borrow = if (d < 0) 1 else 0
            }
            return out
        }

        /** big-endian 바이트 → limb [size]개(little-endian). */
        fun fromBytes(bytes: ByteArray, size: Int): IntArray {
            val out = IntArray(size)
            for ((k, i) in bytes.indices.reversed().withIndex()) {
                if (bytes[i].toInt() == 0 && k / 4 >= size) continue
                require(k / 4 < size) { "수가 ${size * 32}비트보다 큽니다" }
                out[k / 4] = out[k / 4] or ((bytes[i].toInt() and 0xFF) shl (8 * (k % 4)))
            }
            return out
        }

        /** limb → big-endian [length]바이트. */
        fun toBytes(limbs: IntArray, length: Int): ByteArray {
            val out = ByteArray(length)
            for (k in 0 until length) {
                val limb = k / 4
                if (limb < limbs.size) out[length - 1 - k] = (limbs[limb] ushr (8 * (k % 4))).toByte()
            }
            return out
        }
    }
}
