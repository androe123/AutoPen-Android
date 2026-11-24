plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись релиза берётся из переменных окружения (в GitHub Actions — из секретов).
// Если их нет, релизная сборка подписывается debug-ключом.
val keystorePath: String? = System.getenv("AUTOPEN_KEYSTORE_PATH")
val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.androe123.autopen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.androe123.autopen"
        minSdk = 26
        targetSdk = 35
        versionCode = 10202
        versionName = "1.2.2"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("AUTOPEN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AUTOPEN_KEY_ALIAS")
                keyPassword = System.getenv("AUTOPEN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
