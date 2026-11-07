plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val appVersionCode = ciRunNumber?.let { 1000 + it } ?: 6
val appVersionName = ciRunNumber?.let { "0.6.$it" } ?: "0.6"

val stableStorePath = System.getenv("MYCONTACTS_KEYSTORE_PATH")
val stableStorePassword = System.getenv("MYCONTACTS_KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("MYCONTACTS_KEY_ALIAS")
val stableKeyPassword = System.getenv("MYCONTACTS_KEY_PASSWORD")
val stableSigningEnabled = listOf(stableStorePath, stableStorePassword, stableKeyAlias, stableKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.mycontacts"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mycontacts"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
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
            // Isolated package ID avoids signature conflicts with all existing companion variants.
            applicationIdSuffix = ".bridgefix4"
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
    buildFeatures { buildConfig = true; aidl = true }
}
dependencies {
    implementation("io.github.tdlib-android:core:0.1.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
