import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun configuredValue(name: String): String =
    (System.getenv(name) ?: localConfig.getProperty(name) ?: "").trim()

fun quotedBuildValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.remoteapplock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.remoteapplock"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
        buildConfigField("String", "WORKER_URL", quotedBuildValue(configuredValue("REMOTE_APP_LOCK_URL").trimEnd('/')))
        buildConfigField("String", "DEVICE_TOKEN", quotedBuildValue(configuredValue("REMOTE_APP_LOCK_DEVICE_TOKEN")))
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
