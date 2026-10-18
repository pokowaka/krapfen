plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.krapfen.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.krapfen"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = providers.gradleProperty("krapfen.version").get()
    }

    buildTypes {
        // Shrunk/obfuscated like a production app, to verify the library's
        // consumer R8 rules: ./gradlew :app:assembleMinified
        create("minified") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            matchingFallbacks += listOf("release")
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
    implementation(project(":krapfen"))
}
