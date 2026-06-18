import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val popcornAndroidVersionCode = providers.environmentVariable("POPCORN_ANDROID_VERSION_CODE").map(String::toInt).getOrElse(1)
val popcornAndroidVersionName = providers.environmentVariable("POPCORN_ANDROID_VERSION_NAME").getOrElse("0.1.0")
val releaseSigningProperties = loadReleaseSigningProperties()
val releaseSigningRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val releaseSigningComplete = releaseSigningProperties.complete
if (releaseSigningRequested && !releaseSigningComplete) {
    throw GradleException(releaseSigningProperties.errorMessage)
}

android {
    namespace = "dev.popcorn.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.popcorn.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = popcornAndroidVersionCode
        versionName = popcornAndroidVersionName
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningComplete) {
                storeFile = file(releaseSigningProperties.storeFile!!)
                storePassword = releaseSigningProperties.storePassword
                keyAlias = releaseSigningProperties.keyAlias
                keyPassword = releaseSigningProperties.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // Never silently fall back to the debug key for a release build:
            // a debug-signed "release" APK can't update a release-signed install
            // (and vice versa). Leave it unsigned instead so the build/publish
            // fails loudly rather than shipping an un-updatable APK. Release
            // tasks already hard-fail above when signing is incomplete.
            signingConfig = if (releaseSigningComplete) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

data class AndroidReleaseSigning(
    val storeFile: String?,
    val storePassword: String?,
    val keyAlias: String?,
    val keyPassword: String?,
    val propertiesPath: String,
) {
    val complete: Boolean
        get() = !storeFile.isNullOrBlank() && !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()

    val errorMessage: String
        get() = "Android release signing is not configured. Set POPCORN_ANDROID_STORE_FILE, POPCORN_ANDROID_STORE_PASSWORD, POPCORN_ANDROID_KEY_ALIAS, and POPCORN_ANDROID_KEY_PASSWORD, or create $propertiesPath."
}

fun loadReleaseSigningProperties(): AndroidReleaseSigning {
    val defaultPath = "${System.getProperty("user.home")}/.local/android/release-keys/popcorn.properties"
    val propertiesPath = providers.environmentVariable("POPCORN_ANDROID_SIGNING_PROPERTIES").orNull ?: defaultPath
    val props = Properties()
    val propsFile = file(propertiesPath)
    if (propsFile.isFile) {
        propsFile.inputStream().use { props.load(it) }
    }
    fun value(name: String): String? = providers.environmentVariable(name).orNull ?: props.getProperty(name)
    return AndroidReleaseSigning(
        storeFile = value("POPCORN_ANDROID_STORE_FILE"),
        storePassword = value("POPCORN_ANDROID_STORE_PASSWORD"),
        keyAlias = value("POPCORN_ANDROID_KEY_ALIAS"),
        keyPassword = value("POPCORN_ANDROID_KEY_PASSWORD"),
        propertiesPath = propertiesPath,
    )
}
