plugins {
    id("com.android.application")
}

android {
    namespace = "com.autoclicker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autoclicker"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The tablet (Xiaomi Pad 5) is arm64: the OCR library for the other three ABIs made the
        // APK 46 MB and every install ~30 s.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}

dependencies {
    // On-device text reading for the math question: bundled into the APK, no network, no account.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
