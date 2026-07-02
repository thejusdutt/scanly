plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.scanly"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scanly"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ABI scoping is handled by `splits.abi` below (OpenCV + Tesseract ship native
        // libs). Don't also set ndk.abiFilters here — AGP rejects both at once.
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            // Pure FOSS build: OpenCV + Tesseract, no proprietary deps, no INTERNET.
            // See src/foss/AndroidManifest.xml — it does NOT declare INTERNET.
        }
        create("gplay") {
            dimension = "distribution"
            applicationIdSuffix = ".gplay"
            // Adds optional on-device ML Kit + one-time tip jar. Still no subscriptions,
            // and no direct cloud SDKs anywhere — export goes through SAF/share only.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // PdfBox-Android ships duplicate notices.
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Camera
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)

    implementation(libs.coil.compose)

    // CV / OCR / PDF (core, both flavors)
    implementation(libs.opencv)
    "fossImplementation"(libs.tesseract4android)
    "gplayImplementation"(libs.tesseract4android) // fallback OCR also available in gplay
    implementation(libs.pdfbox.android)
    // PDFBox needs BouncyCastle for PDF encryption (password-protected export).
    implementation(libs.bouncycastle.prov)

    // Security
    implementation(libs.security.crypto)
    implementation(libs.sqlcipher)
    implementation(libs.biometric)

    // gplay-only proprietary, on-device
    "gplayImplementation"(libs.mlkit.document.scanner)
    "gplayImplementation"(libs.mlkit.text.recognition)
    "gplayImplementation"(libs.billing.ktx)

    // test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
