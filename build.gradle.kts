plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.aiimage"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    defaultConfig {
        applicationId = "com.example.aiimage"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "3.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
}
