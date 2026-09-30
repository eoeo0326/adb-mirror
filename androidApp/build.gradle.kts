plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.eoeo0326.adbmirror.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.eoeo0326.adbmirror"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("appVersion").get()
    }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
}
