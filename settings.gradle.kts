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
