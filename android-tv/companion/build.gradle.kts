plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val popcornAndroidVersionCode = providers.environmentVariable("POPCORN_ANDROID_VERSION_CODE").map(String::toInt).getOrElse(1)
val popcornAndroidVersionName = providers.environmentVariable("POPCORN_ANDROID_VERSION_NAME").getOrElse("0.1.0")

android {
    namespace = "dev.popcorn.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.popcorn.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = popcornAndroidVersionCode
        versionName = popcornAndroidVersionName
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
