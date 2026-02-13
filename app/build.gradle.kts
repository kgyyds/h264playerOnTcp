plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.kgapph264player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kgapph264player"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")
}