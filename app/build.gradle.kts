plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "gr.autotap"
    compileSdk = 34

    defaultConfig {
        applicationId = "gr.autotap"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Υπογραφή με debug κλειδί ώστε το APK να εγκαθίσταται απευθείας
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Δεν χρειάζονται εξωτερικές βιβλιοθήκες
}
