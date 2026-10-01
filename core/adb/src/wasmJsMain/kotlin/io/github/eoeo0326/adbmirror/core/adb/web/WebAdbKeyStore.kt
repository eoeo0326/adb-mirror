@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.eoeo0326.adbmirror.core.adb.web

import io.github.eoeo0326.adbmirror.core.adb.crypto.AdbRsaKey
import kotlinx.coroutines.await
import kotlin.js.Promise

/** WebCrypto로 RSA 2048 키를 만들어 JWK의 n·d를 hex로 `n:d` 꼴로 돌려준다. */
@JsFun(
    """async () => {
  const pair = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' },
    true, ['sign', 'verify']);
  const jwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
  const hex = (b64u) => {
    const s = atob(b64u.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((b64u.length + 3) % 4));
    let h = ''; for (let i = 0; i < s.length; i++) h += s.charCodeAt(i).toString(16).padStart(2, '0');
    return h;
  };
  return hex(jwk.n) + ':' + hex(jwk.d);
}""",
)
private external fun generateKeyHex(): Promise<JsString>

@JsFun("(k) => { try { return localStorage.getItem(k); } catch (e) { return null; } }")
private external fun storageGet(key: String): String?

@JsFun("(k, v) => { try { localStorage.setItem(k, v); } catch (e) {} }")
private external fun storageSet(key: String, value: String)

/**
 * 브라우저의 adb 키. 처음에 WebCrypto로 만들어 localStorage에 둔다(이 사이트 출처에서만 읽힘).
 * 서명은 해시를 다시 하지 않는 순수 Kotlin RSA로 해야 해서 개인키를 내보낼 수 있게 만든다.
 */
object WebAdbKeyStore {
    private const val STORAGE_KEY = "adb-mirror.adbkey"

    /** 기기의 허용 창과 `adb` 키 목록에 보이는 이름. */
    const val KEY_NAME = "adb-mirror@browser"

    suspend fun loadOrCreate(): AdbRsaKey {
        val text = storageGet(STORAGE_KEY) ?: generateKeyHex().await<JsString>().toString().also { storageSet(STORAGE_KEY, it) }
        val (n, d) = text.split(':')
        return AdbRsaKey(n.hexToByteArray(), d.hexToByteArray())
    }
}
