plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saqmusheer.simplearmeasure"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.saqmusheer.simplearmeasure"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    buildFeatures {
        viewBinding = true
    }

    // Keep native AR libraries compressed so older 4 KB ELF binaries
    // can be extracted and run under Android 16 page-size compatibility.
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.gorisse.thomas.sceneform:sceneform:1.23.0")
    implementation("com.gorisse.thomas.sceneform:ux:1.23.0")
}
