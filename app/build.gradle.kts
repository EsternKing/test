plugins {
    id("com.android.application")
}

android {
    namespace = "com.saypod.aicorediag"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.saypod.aicorediag"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
