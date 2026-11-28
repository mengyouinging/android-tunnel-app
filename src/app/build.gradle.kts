plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.example.androidtunnel"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.example.androidtunnel"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.5"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_TUNNEL_KEYSTORE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_TUNNEL_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_TUNNEL_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_TUNNEL_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
