import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

val javacppPlatform: String by rootProject.extra

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "io.github.eoeo0326.adbmirror.core.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            api(projects.core.adb)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Desktop(jvm)과 Android가 함께 쓰는 java.io 기반 코드(녹화 파일 쓰기 등).
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)
        androidMain.dependencies {
            implementation(libs.androidx.core) // FileProvider(스크린샷 클립보드)
        }
        jvmMain.dependencies {
            // 녹화 파일 디코딩(avformat·avcodec)과 WebP 프레임 인코딩(libwebp). 빌드하는 OS 것만 넣는다.
            implementation(libs.bytedeco.ffmpeg)
            implementation("org.bytedeco:ffmpeg:${libs.versions.bytedeco.ffmpeg.get()}:$javacppPlatform")
            implementation("org.bytedeco:javacpp:${libs.versions.bytedeco.javacpp.get()}:$javacppPlatform")
        }
    }
}

// jvmTest의 fixture 테스트가 레포 루트의 fixtures/를 읽는다.
tasks.withType<Test>().configureEach {
    systemProperty("fixtures.dir", rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    systemProperty("scrcpy.version", providers.gradleProperty("scrcpy.version").get())
    systemProperty("scrcpy.sha256", providers.gradleProperty("scrcpy.sha256").get())
    // 실기기 통합 테스트: -Padbmirror.device=<serial>
    systemProperty("adbmirror.device", providers.gradleProperty("adbmirror.device").getOrElse(""))
}

/** scrcpy-server를 GitHub 릴리즈에서 받아 sha256을 검증하고 JVM 리소스로 둔다. */
abstract class FetchScrcpyServer : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val v = version.get()
        val url = URI("https://github.com/Genymobile/scrcpy/releases/download/v$v/scrcpy-server-v$v").toURL()
        val bytes = url.openStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == sha256.get()) { "scrcpy-server sha256 불일치: expected=${sha256.get()} actual=$actual" }
        val dir = outputDir.get().asFile.resolve("io/github/eoeo0326/adbmirror/scrcpy").apply { mkdirs() }
        dir.resolve("scrcpy-server").writeBytes(bytes)
        dir.resolve("scrcpy-server.properties").writeText("version=$v\n")
    }
}

val fetchScrcpyServer = tasks.register<FetchScrcpyServer>("fetchScrcpyServer") {
    version = providers.gradleProperty("scrcpy.version")
    sha256 = providers.gradleProperty("scrcpy.sha256")
    outputDir = layout.buildDirectory.dir("generated/scrcpy-server")
}

kotlin.sourceSets.getByName("jvmMain").resources.srcDir(fetchScrcpyServer)
// Android 앱도 같은 서버를 Java 리소스로 싣는다(Kadb로 기기에 push).
kotlin.sourceSets.getByName("androidMain").resources.srcDir(fetchScrcpyServer)
