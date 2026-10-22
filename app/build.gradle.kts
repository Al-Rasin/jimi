plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

ksp {
    // Room schema JSON per version (small, in git) — migrations are checked against it
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.alrasin.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alrasin.jarvis"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Only the Redmi (arm64): smaller APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
    // Wake word ONNX models are not in git. Put them in
    // models/wakeword/app-assets/wakeword/ — see training/wakeword/README.md.
    sourceSets["main"].assets.srcDir(rootProject.file("models/wakeword/app-assets"))
    // Speech-to-text + voice models (D-025), from tools/fetch_speech.sh: models/speech/app-assets/{stt,tts}/
    sourceSets["main"].assets.srcDir(rootProject.file("models/speech/app-assets"))
    androidResources {
        noCompress += "onnx"
    }
    packaging {
        // sherpa AAR and onnxruntime-android both have lib/x86/libonnxruntime.so; x86 is not shipped anyway
        jniLibs.excludes += "lib/x86/libonnxruntime.so"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.onnxruntime.android)
    // sherpa-onnx (D-006) with onnxruntime linked inside, so it does not clash with onnxruntime-android.
    // Not in git (big binary): tools/fetch_speech.sh downloads it.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-${libs.versions.sherpa.onnx.get()}.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libsu.core)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
}
