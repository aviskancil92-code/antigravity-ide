import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing rilis opsional: buat berkas `keystore.properties` di root proyek
// berisi: storeFile, storePassword, keyAlias, keyPassword.
// Lihat README.md bagian "APK rilis bertanda tangan".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.antigravity.ide"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antigravity.ide"
        minSdk = 26
        // PENTING: targetSdk sengaja 28 (strategi yang sama dengan Termux).
        // Android 10+ (API 29+) melarang exec() berkas dari penyimpanan aplikasi,
        // padahal proot & seluruh biner glibc Debian harus dieksekusi dari filesDir.
        // Dengan targetSdk 28, menjalankan userland Linux tanpa root tetap legal.
        // Jangan naikkan ke 29+ kecuali Anda tahu konsekuensinya.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // targetSdk 28 disengaja (kompatibilitas proot ala Termux) — matikan
        // peringatan lint terkait kebijakan Play Store (APK ini untuk sideload).
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Dekompresi XZ (rootfs Debian .tar.xz) — murni Java, aman di Android.
    implementation("org.tukaani:xz:1.9")
}
