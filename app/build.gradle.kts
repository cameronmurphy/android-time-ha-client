import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing details, kept outside the project.
 *
 * The debug key that Gradle generates is a publicly known certificate, and Play Protect
 * hard-blocks installs signed with it ("App blocked to protect your device"). The real
 * keystore lives in a backed-up location because losing it means an installed copy can
 * never be updated in place again — only uninstalled and reinstalled.
 *
 * Override the location with -PsigningProperties=/path/to/file.properties.
 */
val signingPropertiesPath: String = (project.findProperty("signingProperties") as String?)
    ?: "/Volumes/Media/GDriveCam/keys/android-time-ha-client.properties"

val signingProperties = Properties().apply {
    val file = File(signingPropertiesPath)
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.camurphy.android_time_ha_client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.camurphy.android_time_ha_client"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    signingConfigs {
        if (signingProperties.isNotEmpty()) {
            create("release") {
                storeFile = File(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                // minSdk is 30, so the v1 JAR signature is dead weight; v3 carries the
                // key-rotation lineage if this key ever has to be replaced.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val release = signingConfigs.findByName("release")
            if (release != null) {
                signingConfig = release
            } else {
                logger.warn(
                    "WARNING: $signingPropertiesPath not found — the release build will be " +
                        "signed with the debug key and Play Protect will block it."
                )
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
