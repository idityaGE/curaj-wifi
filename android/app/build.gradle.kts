import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.sentry.android.gradle")
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

// Sentry's DSN is public, but keep machine-specific configuration outside the
// repository like the release signing material. The auth token may come from
// the environment or the local mode-600 properties file. It is never written
// into the APK or the repository.
val sentryPropertiesFile = file(
    System.getenv("CURAJ_SENTRY_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/curaj-wifi/sentry.properties"
)
val sentryProperties = Properties().apply {
    if (sentryPropertiesFile.isFile) {
        sentryPropertiesFile.inputStream().use { load(it) }
    }
}
val sentryDsn = System.getenv("SENTRY_DSN")
    ?: sentryProperties.getProperty("dsn")
    ?: ""
val sentryAuthToken = System.getenv("SENTRY_AUTH_TOKEN")
    ?: sentryProperties.getProperty("authToken")

android {
    namespace = "in.ac.curaj.autologin"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.ac.curaj.autologin"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.6.1"
        buildConfigField(
            "String",
            "SENTRY_DSN",
            "\"${sentryDsn.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
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

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

sentry {
    // Source context is uploaded only when a token is available. This keeps
    // ordinary debug/unsigned builds working without a Sentry login.
    includeSourceContext.set(!sentryAuthToken.isNullOrBlank())
    org.set("idityage")
    projectName.set("curaj-wifi")
    authToken.set(sentryAuthToken)
    autoUploadProguardMapping.set(true)
    telemetry.set(false)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("io.sentry:sentry-bom:8.61.1"))
    implementation("io.sentry:sentry-android")
}
