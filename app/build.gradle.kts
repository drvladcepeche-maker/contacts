plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.mycontacts"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mycontacts"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }
    val stableSigningPath = System.getenv("MYCONTACTS_KEYSTORE_PATH")
    if (!stableSigningPath.isNullOrBlank()) {
        signingConfigs {
            create("stable") {
                storeFile = file(stableSigningPath)
                storePassword = System.getenv("MYCONTACTS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MYCONTACTS_KEY_ALIAS")
                keyPassword = System.getenv("MYCONTACTS_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (!stableSigningPath.isNullOrBlank()) signingConfig = signingConfigs.getByName("stable")
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("io.github.tdlib-android:core:0.1.1")
}
