@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.core.data.web

import io.github.eoeo0326.adbmirror.core.data.scrcpy.ServerJar
import io.github.eoeo0326.adbmirror.core.data.scrcpy.ServerJarSource
import io.github.eoeo0326.adbmirror.core.data.storage.TextStore
import kotlinx.coroutines.await
import kotlin.js.Promise

/** 응답 본문(`Uint8Array`). 호출마다 따로 돌려줘 동시에 받아도 섞이지 않는다. */
@JsFun(
    """async (url) => {
  const r = await fetch(url);
  if (!r.ok) throw new Error(url + ' ' + r.status);
  return new Uint8Array(await r.arrayBuffer());
}""",
)
private external fun fetchBytes(url: String): Promise<JsAny>

@JsFun("(a) => a.length")
private external fun bytesLength(array: JsAny): Int

@JsFun("(a, i) => a[i]")
private external fun byteAt(array: JsAny, index: Int): Int

@JsFun("async (url) => { const r = await fetch(url); if (!r.ok) throw new Error(url + ' ' + r.status); return await r.text(); }")
private external fun fetchText(url: String): Promise<JsString>

/** 웹앱과 함께 배포한 파일([url]은 페이지 기준 상대 경로)을 받아 온다. */
suspend fun fetchResource(url: String): ByteArray {
    val data = fetchBytes(url).await<JsAny>()
    return ByteArray(bytesLength(data)) { byteAt(data, it).toByte() }
}

/** 웹앱과 함께 배포한 scrcpy-server(빌드할 때 Gradle이 받아 넣음)를 받아 온다. */
object FetchServerJarSource : ServerJarSource {
    private const val DIR = "io/github/eoeo0326/adbmirror/scrcpy"

    override suspend fun load(): ServerJar {
        val version = fetchText("$DIR/scrcpy-server.properties").await<JsString>().toString()
            .lines().first { it.startsWith("version=") }.substringAfter('=').trim()
        return ServerJar(version, fetchResource("$DIR/scrcpy-server"))
    }
}

@JsFun("(k) => { try { return localStorage.getItem(k); } catch (e) { return null; } }")
private external fun storageGet(key: String): String?

@JsFun("(k, v) => { try { localStorage.setItem(k, v); } catch (e) {} }")
private external fun storageSet(key: String, value: String)

/** 이 사이트의 localStorage 항목 하나. */
class LocalStorageTextStore(private val key: String) : TextStore {
    override suspend fun read(): String? = storageGet(key)
    override suspend fun write(text: String) = storageSet(key, text)
}
