plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The C++ stacker is optional: `-PnoNative` builds the Kotlin-only library.
val withNative = !project.hasProperty("noNative")

android {
    namespace = "com.proshot.detail"
    compileSdk = 34

    // Prefer the NDK that CI images already carry over downloading one.
    System.getenv("ANDROID_NDK_LATEST_HOME")?.let { ndkPath = it }

    defaultConfig {
        minSdk = 29
        if (withNative) {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    if (withNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
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
