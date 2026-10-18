plugins {
    // >= 8.5.1 is required to 16 KB-align native libraries inside the APK.
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
}
