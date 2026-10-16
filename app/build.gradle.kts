import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signalKeystorePath = System.getenv("SIGNAL_KEYSTORE_PATH")
val signalStorePassword = System.getenv("SIGNAL_KEYSTORE_PASSWORD")
val signalKeyAlias = System.getenv("SIGNAL_KEY_ALIAS")
val signalKeyPassword = System.getenv("SIGNAL_KEY_PASSWORD")
val hasSignalSigning = listOf(signalKeystorePath, signalStorePassword, signalKeyAlias, signalKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.wb.signalpro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wb.signalpro"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.2.0-alpha.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSignalSigning) {
            create("signalRelease") {
                storeFile = file(signalKeystorePath!!)
                storePassword = signalStorePassword
                keyAlias = signalKeyAlias
                keyPassword = signalKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSignalSigning) {
                signingConfig = signingConfigs.getByName("signalRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

configurations.configureEach {
    exclude(group = "androidx.annotation", module = "annotation")
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("me.impa:icmpenguin:1.0.0-rc.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
