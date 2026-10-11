plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wb.signalpro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wb.signalpro"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-dev"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("me.impa:icmpenguin:1.0.0-rc.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
