plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize") version "1.9.24" apply false
    // TDLib native Android binaries and Java API (4 ABIs).
    // Authentication is user-driven; API credentials are entered locally and never uploaded.
    dependencies {
        implementation("io.github.tdlib-android:core:0.1.1")
    }
}
android {
    namespace = "com.mycontacts"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mycontacts"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
