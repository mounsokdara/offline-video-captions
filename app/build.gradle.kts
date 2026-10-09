plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.videocaptions"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.videocaptions"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    dependenciesInfo { includeInApk = false; includeInBundle = false }
    packaging {
        resources {
            excludes += listOf("/META-INF/**", "/kotlin/**", "**.kotlin_builtins", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
    }
}
