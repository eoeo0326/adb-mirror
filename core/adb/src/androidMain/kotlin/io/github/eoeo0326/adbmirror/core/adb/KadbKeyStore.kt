package io.github.eoeo0326.adbmirror.core.adb

import com.flyfishxu.kadb.cert.KadbCert
import java.io.File

/**
 * 이 앱의 adb 키(인증서·개인키)를 [dir]에 두고 Kadb에 넣는다. Kadb는 키를 메모리에만 들고 있어서,
 * 저장하지 않으면 앱을 다시 켤 때마다 새 키가 되어 페어링을 다시 해야 한다.
 * 기기는 공개키로 신뢰하므로 인증서 유효 기간은 길게(10년) 잡는다(Kadb 기본은 120일).
 */
class KadbKeyStore(private val dir: File) {
    private val certFile get() = File(dir, "adbkey.cert")
    private val keyFile get() = File(dir, "adbkey.key")

    /** 저장된 키가 있고 유효하면 그것을, 아니면 새로 만들어 저장한 뒤 Kadb에 넣는다. */
    @Synchronized
    fun install() {
        if (certFile.isFile && keyFile.isFile) {
            val loaded = runCatching { KadbCert.set(certFile.readBytes(), keyFile.readBytes()) }
            if (loaded.isSuccess) return
        }
        val (cert, key) = KadbCert.get(notAfter = System.currentTimeMillis() + TEN_YEARS_MS)
        dir.mkdirs()
        certFile.writeBytes(cert)
        keyFile.writeBytes(key)
    }

    private companion object {
        const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
