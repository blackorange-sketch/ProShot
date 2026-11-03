plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.proshot.camera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proshot.camera"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.0"
    }

    // A fixed release key (supplied by CI from secrets) lets every build be installed over the previous one.
    // Without it (local builds, forks) the default debug key is used.
    val ksPath = System.getenv("KEYSTORE_FILE")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    val hasKey = ksPath != null && ksPass != null && File(ksPath).exists()
    if (hasKey) {
        signingConfigs {
            create("proshot") {
                storeFile = File(ksPath!!)
                storePassword = ksPass
                keyAlias = "proshot"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        debug {
            if (hasKey) signingConfig = signingConfigs.getByName("proshot")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("proshot") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation(project(":detail"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
