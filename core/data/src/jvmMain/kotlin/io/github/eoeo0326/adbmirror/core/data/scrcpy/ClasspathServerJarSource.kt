package io.github.eoeo0326.adbmirror.core.data.scrcpy

import java.util.Properties

/** Gradle `fetchScrcpyServer` 작업이 JVM 리소스로 넣은 scrcpy-server를 읽는다. */
object ClasspathServerJarSource : ServerJarSource {
    private const val DIR = "/io/github/eoeo0326/adbmirror/scrcpy"

    override suspend fun load(): ServerJar {
        val owner = ClasspathServerJarSource::class.java
        val props = Properties()
        (owner.getResourceAsStream("$DIR/scrcpy-server.properties") ?: error("scrcpy-server.properties 리소스가 없습니다"))
            .use(props::load)
        val bytes = (owner.getResourceAsStream("$DIR/scrcpy-server") ?: error("scrcpy-server 리소스가 없습니다"))
            .use { it.readBytes() }
        return ServerJar(props.getProperty("version"), bytes)
    }
}
