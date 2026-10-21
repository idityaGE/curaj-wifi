import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing material lives outside the repository. On this machine the
// default is ~/.config/curaj-wifi/release-signing.properties. CI can point
// CURAJ_SIGNING_PROPERTIES at a temporary file created from repository secrets.
val signingPropertiesFile = file(
    System.getenv("CURAJ_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/curaj-wifi/release-signing.properties"
)
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = signingPropertiesFile.isFile

android {
    namespace = "in.ac.curaj.autologin"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.ac.curaj.autologin"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(
                    signingProperties.getProperty("storeFile")
                        ?: error("storeFile missing in $signingPropertiesFile")
                )
                storePassword = signingProperties.getProperty("storePassword")
                    ?: error("storePassword missing in $signingPropertiesFile")
                keyAlias = signingProperties.getProperty("keyAlias")
                    ?: error("keyAlias missing in $signingPropertiesFile")
                keyPassword = signingProperties.getProperty("keyPassword")
                    ?: error("keyPassword missing in $signingPropertiesFile")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
