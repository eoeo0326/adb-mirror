rootProject.name = "adb-mirror"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Kadb가 쓰는 spake2(무선 페어링)만 JitPack에 있다. 그 그룹(spake2, spake2-android …)만 받도록 막아 둔다.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.flyfishxu.spake2-java") }
        }
    }
}

include(
    ":core:domain",
    ":core:adb",
    ":core:data",
    ":feature:mirror",
    ":composeApp",
    ":androidApp",
)
