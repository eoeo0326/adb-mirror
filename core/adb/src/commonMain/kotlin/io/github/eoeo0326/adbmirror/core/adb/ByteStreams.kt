package io.github.eoeo0326.adbmirror.core.adb

/** 기기와 이어진 스트림의 읽기 쪽. 플랫폼 전송(adb 소켓, Kadb, WebUSB)이 구현한다. */
interface ByteSource {
    /**
     * 정확히 [count]바이트를 읽을 때까지 기다린다.
     * 그 전에 스트림이 끝나면 [EndOfStreamException]을 던진다.
     */
    suspend fun readFully(count: Int): ByteArray

    suspend fun close()
}

/** 기기와 이어진 스트림의 쓰기 쪽. */
interface ByteSink {
    suspend fun write(bytes: ByteArray)

    suspend fun close()
}

class EndOfStreamException(message: String = "스트림이 끝났습니다") : Exception(message)

/** 메모리의 바이트 배열을 읽는 [ByteSource]. 테스트와 fixture 재생에 쓴다. */
class ByteArraySource(private val data: ByteArray) : ByteSource {
    private var position = 0

    val remaining: Int get() = data.size - position

    override suspend fun readFully(count: Int): ByteArray {
        require(count >= 0) { "count는 0 이상이어야 한다: $count" }
        if (count > remaining) {
            position = data.size
            throw EndOfStreamException()
        }
        return data.copyOfRange(position, position + count).also { position += count }
    }

    override suspend fun close() {
        position = data.size
    }
}
