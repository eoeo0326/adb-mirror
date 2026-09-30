package io.github.eoeo0326.adbmirror.core.data.recording

/** ISO BMFF(MP4) 상자를 big-endian으로 쌓는 작은 버퍼. */
internal class BoxWriter {
    private var buffer = ByteArray(256)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun u8(v: Int) = apply { ensure(1); buffer[size++] = v.toByte() }
    fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
    fun u24(v: Int) = apply { u8(v ushr 16); u16(v) }
    fun u32(v: Long) = apply { u16((v ushr 16).toInt()); u16(v.toInt()) }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) = apply { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
    fun bytes(b: ByteArray) = apply { ensure(b.size); b.copyInto(buffer, size); size += b.size }
    fun fourcc(s: String) = apply { require(s.length == 4); s.forEach { u8(it.code) } }
    fun zeros(n: Int) = apply { repeat(n) { u8(0) } }

    /** 크기 필드를 나중에 채우는 상자. [body] 안에서 자식 상자를 쓴다. */
    fun box(type: String, body: BoxWriter.() -> Unit) = apply {
        val start = size
        u32(0)
        fourcc(type)
        body()
        patchU32(start, size - start)
    }

    /** version(1B) + flags(3B)가 붙는 full box. */
    fun fullBox(type: String, version: Int, flags: Int, body: BoxWriter.() -> Unit) =
        box(type) { u8(version); u24(flags); body() }

    fun patchU32(offset: Int, v: Int) {
        buffer[offset] = (v ushr 24).toByte()
        buffer[offset + 1] = (v ushr 16).toByte()
        buffer[offset + 2] = (v ushr 8).toByte()
        buffer[offset + 3] = v.toByte()
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
