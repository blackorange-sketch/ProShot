plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The C++ stacker is optional: `-PnoNative` builds the Kotlin-only library.
val withNative = !project.hasProperty("noNative")

android {
    namespace = "com.proshot.detail"
    compileSdk = 34

    if (withNative) {
        // Use the NDK version the CI image already has instead of AGP's default one.
        val ndkDir = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
        val ndkName = ndkDir?.let { File(it).name }
        if (ndkName != null && Regex("""\d+\.\d+\.\d+""").matches(ndkName)) {
            ndkVersion = ndkName
        }
    }

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
