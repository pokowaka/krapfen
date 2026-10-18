plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.krapfen"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.krapfen"
        // AAudio is available from API 26; miniaudio falls back to OpenSL ES.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"

        externalNativeBuild {
            cmake {
                cFlags += listOf("-O2")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
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
