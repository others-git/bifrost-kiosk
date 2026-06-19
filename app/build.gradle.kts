plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "live.theundead.bifrost.kiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "live.theundead.bifrost.kiosk"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Keep only the wall tablet's ABI so the Vosk native libs don't bloat the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        viewBinding = true
        // Expose VERSION_NAME so the WebView can stamp it into its User-Agent
        // (the marker the Bifrost web app keys on to hide kiosk-inappropriate UI).
        buildConfig = true
    }
    // The Vosk model (if bundled) ships uncompressed so it can be mmap'd at runtime.
    androidResources {
        noCompress += listOf("mdl", "fst", "conf", "int", "txt", "carpa", "ie")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // On-device wake-word (keyword spotting) + STT. FOSS, CPU-only, offline.
    // Model is loaded from assets at runtime; see scripts/fetch-vosk-model.sh.
    implementation("com.alphacephei:vosk-android:0.3.47")

    // QR scanner for device pairing. ZXing (Apache-2.0), pure-FOSS, works fully
    // offline — NO Google Play Services / ML Kit, so it's safe on the de-Googled
    // tablet. Used only by the maintenance-screen "Scan pairing QR" action.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json so unit tests exercise grammar/vocab JSON (the android.jar
    // stub throws "not mocked"); production uses the framework's bundled copy.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
