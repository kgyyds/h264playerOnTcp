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

    // 加上这一段，统一 Java 和 Kotlin 版本
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")
}
