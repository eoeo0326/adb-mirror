package io.github.eoeo0326.adbmirror.core.adb

/** `adb devices -l` / `adb track-devices -l` 출력 해석. */
internal object AdbOutputParser {
    /** 한 줄: `SERIAL<탭>state product:x model:SM_N976N device:y transport_id:1` */
    fun parseDevices(text: String): List<AdbDevice> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val model = parts.drop(2).firstOrNull { it.startsWith("model:") }?.removePrefix("model:")?.replace('_', ' ')
            AdbDevice(parts[0], parts[1], model)
        }
        .toList()

    /**
     * track-devices 스트림에서 4자리 16진수 길이 + 본문 메시지를 하나씩 꺼낸다. 덜 온 메시지는 남긴다.
     * adb가 서버를 띄우며 찍는 `* daemon …` 안내 줄이 섞여 오면 건너뛴다.
     */
    fun takeTrackMessages(buffer: StringBuilder): List<String> {
        val messages = mutableListOf<String>()
        while (true) {
            if (buffer.isNotEmpty() && buffer[0] == '*') {
                val end = buffer.indexOf("\n")
                if (end < 0) break // 안내 줄이 덜 왔다
                buffer.delete(0, end + 1)
                continue
            }
            if (buffer.length < 4) break
            val length = buffer.substring(0, 4).toIntOrNull(16) ?: throw AdbException("track-devices 길이 해석 실패: ${buffer.take(4)}")
            if (buffer.length < 4 + length) break
            messages += buffer.substring(4, 4 + length)
            buffer.delete(0, 4 + length)
        }
        return messages
    }
}
