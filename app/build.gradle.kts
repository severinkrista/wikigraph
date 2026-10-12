import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Use the repository's fixed signing key for every debug APK, both locally and in GitHub Actions.
// This avoids Android Studio's machine-specific ~/.android/debug.keystore changing the app signature.
val stableDebugKeystoreSource = rootProject.file("ci/debug.keystore.b64")
val stableDebugKeystore = layout.buildDirectory.file("signing/debug.keystore").get().asFile
if (stableDebugKeystoreSource.exists()) {
    stableDebugKeystore.parentFile.mkdirs()
    stableDebugKeystore.writeBytes(
        Base64.getMimeDecoder().decode(stableDebugKeystoreSource.readText())
    )
}

android {
    namespace = "ru.wikigraph"
    compileSdk = 35

    signingConfigs {
        create("stableDebug") {
            storeFile = stableDebugKeystore
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "ru.wikigraph"
        minSdk = 26
        targetSdk = 35
        versionCode = 503
        versionName = "2.12.3"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
