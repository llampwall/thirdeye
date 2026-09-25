import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thirdeye.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thirdeye.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation("com.meta.wearable:mwdat-core:1.0.0")
    implementation("com.meta.wearable:mwdat-camera:1.0.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
