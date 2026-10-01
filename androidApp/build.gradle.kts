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
    implementation(projects.feature.mirror)
    implementation(projects.core.data)
    implementation(projects.core.adb)
    implementation(projects.core.domain)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core) // 알림(NotificationCompat·RemoteInput)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
