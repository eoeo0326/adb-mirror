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

    /** `adb pair` 출력. 성공하면 `Successfully paired to …`를 찍는다(실패해도 exit 0인 adb 버전이 있다). */
    fun pairFailure(output: String): String? =
        if (output.lineSequence().any { it.trim().startsWith("Successfully paired") }) null
        else failure("페어링하지 못했습니다", output)

    /**
     * `adb connect` 출력. 연결하지 못해도 exit 0으로 끝나는 경우가 많아 문구로 판정한다.
     * 성공은 `connected to …` 또는 `already connected to …`.
     */
    fun connectFailure(output: String): String? {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val ok = lines.any { it.startsWith("connected to ") || it.startsWith("already connected to ") }
        return if (ok) null else failure("연결하지 못했습니다", output)
    }

    private fun failure(title: String, output: String): String {
        val detail = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return if (detail.isEmpty()) title else "$title: $detail"
    }

    /** track-devices 스트림에서 4자리 16진수 길이 + 본문 메시지를 하나씩 꺼낸다. 덜 온 메시지는 남긴다. */
    fun takeTrackMessages(buffer: StringBuilder): List<String> {
        val messages = mutableListOf<String>()
        while (buffer.length >= 4) {
            val length = buffer.substring(0, 4).toIntOrNull(16) ?: throw AdbException("track-devices 길이 해석 실패: ${buffer.take(4)}")
            if (buffer.length < 4 + length) break
            messages += buffer.substring(4, 4 + length)
            buffer.delete(0, 4 + length)
        }
        return messages
    }
}
