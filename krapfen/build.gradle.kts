plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

val krapfenGroup = providers.gradleProperty("krapfen.group").get()
val krapfenVersion = providers.gradleProperty("krapfen.version").get()

android {
    namespace = "dev.krapfen"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // AAudio is available from API 26; miniaudio falls back to OpenSL ES.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION", "\"$krapfenVersion\"")

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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // Every public declaration must state its visibility: keeps the API
    // surface deliberate.
    explicitApi()
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = krapfenGroup
            artifactId = "krapfen"
            version = krapfenVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Krapfen")
                description.set(
                    "Plays Strudel patterns on Android: Strudel's pattern engine in QuickJS " +
                        "with the native dough synth. Unofficial, not affiliated with Strudel."
                )
                url.set("https://github.com/pokowaka/krapfen")
                licenses {
                    license {
                        name.set("GNU Affero General Public License v3.0 or later")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                    }
                }
            }
        }
    }
    repositories {
        // ./gradlew :krapfen:publishReleasePublicationToLocalRepository
        maven {
            name = "local"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
