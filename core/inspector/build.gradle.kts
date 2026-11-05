plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

// Desktop 전용. Android Studio transport가 gRPC(HTTP/2)라 Android·Web 타깃은 두지 않는다.
kotlin {
    jvmToolchain(21)
}

dependencies {
    api(projects.core.domain)
    api(projects.core.adb)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.protobuf.kotlin)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.kotlin.stub)
    runtimeOnly(libs.grpc.netty.shaded)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.grpc.inprocess)
}

// proto 대조(ProtoCompatibilityTest): -Padbmirror.studio=<Android Studio 설치 경로>
// 실기기 덤프(InspectorDeviceDumpTest): -Padbmirror.device=<serial> -Padbmirror.inspectPackage=<패키지> [-Pinspector.tapAt=x,y]
tasks.withType<Test>().configureEach {
    systemProperty("adbmirror.studio", providers.gradleProperty("adbmirror.studio").getOrElse(""))
    systemProperty("adbmirror.device", providers.gradleProperty("adbmirror.device").getOrElse(""))
    systemProperty("adbmirror.inspectPackage", providers.gradleProperty("adbmirror.inspectPackage").getOrElse(""))
    systemProperty("inspector.tapAt", providers.gradleProperty("inspector.tapAt").getOrElse(""))
    systemProperty("inspector.dumpDir", layout.buildDirectory.dir("inspector-dump").get().asFile.absolutePath)
    testLogging { if (providers.gradleProperty("adbmirror.device").isPresent) showStandardStreams = true }
}

// 메시지만 생성한다. gRPC 서비스는 TransportClient에서 MethodDescriptor로 직접 정의해
// 러너마다 네이티브 gRPC 코드 생성 플러그인을 받지 않는다.
protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().configureEach {
            builtins { create("kotlin") }
        }
    }
}
