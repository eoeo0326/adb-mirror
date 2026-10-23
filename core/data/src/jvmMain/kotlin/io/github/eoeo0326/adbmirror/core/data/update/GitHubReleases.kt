package io.github.eoeo0326.adbmirror.core.data.update

import io.github.eoeo0326.adbmirror.core.domain.model.AppVersion

/**
 * GitHub Releases 주소 규칙. 최신 버전은 `releases/latest`가 돌려주는 리다이렉트 주소(`…/releases/tag/vX.Y.Z`)에서 읽는다.
 * API·JSON을 쓰지 않아 요청 횟수 제한에 걸리지 않는다.
 */
class GitHubReleases(private val repo: String = "eoeo0326/adb-mirror") {
    val latestUrl: String get() = "https://github.com/$repo/releases/latest"

    fun notesUrl(version: AppVersion) = "https://github.com/$repo/releases/tag/v$version"

    fun assetUrl(version: AppVersion, name: String) = "https://github.com/$repo/releases/download/v$version/$name"

    companion object {
        private val TAG = Regex("""/releases/tag/(v?\d+\.\d+\.\d+)/?$""")

        /** 리다이렉트 주소에서 버전을 읽는다. 릴리즈가 없으면(목록 페이지로 감) null. */
        fun versionFromLocation(location: String): AppVersion? = TAG.find(location.trim())?.groupValues?.get(1)?.let(AppVersion::parse)

        /** `sha256sum` 출력(`<hex>  <파일명>`, 바이너리 모드면 `*<파일명>`)을 파일명 → 해시로 읽는다. */
        fun parseSha256Sums(text: String): Map<String, String> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("""\s+"""), limit = 2)
                if (parts.size != 2 || parts[0].length != 64) null else parts[1].removePrefix("*") to parts[0].lowercase()
            }
            .toMap()
    }
}
