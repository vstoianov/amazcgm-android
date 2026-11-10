import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore.properties in the project root, or AMAZCGM_* environment
// variables. Without either, release builds fall back to the debug key.
// See RELEASING.md.
val releaseSigning: Map<String, String?> = run {
    val props = Properties()
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { props.load(it) }
    fun value(key: String, env: String) = props.getProperty(key) ?: System.getenv(env)
    mapOf(
        "storeFile" to value("storeFile", "AMAZCGM_KEYSTORE"),
        "storePassword" to value("storePassword", "AMAZCGM_KEYSTORE_PASSWORD"),
        "keyAlias" to value("keyAlias", "AMAZCGM_KEY_ALIAS"),
        "keyPassword" to value("keyPassword", "AMAZCGM_KEY_PASSWORD")
    )
}
val hasReleaseSigning = releaseSigning.values.all { !it.isNullOrBlank() }

android {
    namespace = "com.vstoianov.amazcgm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vstoianov.amazcgm"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.10"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val path = releaseSigning.getValue("storeFile")!!
                // Relative paths are relative to the project root.
                storeFile = rootProject.file(
                    if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path
                )
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
