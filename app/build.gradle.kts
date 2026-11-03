plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableStorePath = System.getenv("MYCONTACTS_KEYSTORE_PATH")
val stableStorePassword = System.getenv("MYCONTACTS_KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("MYCONTACTS_KEY_ALIAS")
val stableKeyPassword = System.getenv("MYCONTACTS_KEY_PASSWORD")
val stableSigningEnabled = listOf(stableStorePath, stableStorePassword, stableKeyAlias, stableKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.mycontacts"
    compileSdk = 34
    defaultConfig {
        // Separate application identity: installs alongside the original app.
        // Keep this ID unchanged for every future recovery-app update.
        applicationId = "com.mycontacts.recovery"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-recovery"
    }
    signingConfigs {
        if (stableSigningEnabled) {
            create("stable") {
                storeFile = file(stableStorePath!!)
                storePassword = stableStorePassword!!
                keyAlias = stableKeyAlias!!
                keyPassword = stableKeyPassword!!
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (stableSigningEnabled) signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (stableSigningEnabled) signingConfig = signingConfigs.getByName("stable")
        }
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
