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

val llamaSource = file("src/main/cpp/llama.cpp/CMakeLists.txt").exists()

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
        if (llamaSource) {
            externalNativeBuild {
                cmake {
                    // Release-speed native code even in debug APKs: a -O0 llama.cpp is ~10x slower
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_PLATFORM=android-30")
                    cFlags += "-O3"
                    cppFlags += listOf("-O3", "-std=c++17")
                }
            }
        }
    }

    // Local LLM (PLAN 9.1): built only after tools/fetch_llama.sh put llama.cpp in app/src/main/cpp/.
    if (llamaSource) {
        ndkVersion = "27.0.12077973"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
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
    // android.* stubs return 0/null in JVM tests (the tool list is built in ToolPromptDump without a phone)
    testOptions { unitTests.isReturnDefaultValues = true }

    androidResources {
        noCompress += "onnx"
    }
    packaging {
        // sherpa AAR and onnxruntime-android both have lib/x86/libonnxruntime.so; x86 is not shipped anyway
        jniLibs.excludes += "lib/x86/libonnxruntime.so"
        // duplicate licence files from the Anthropic SDK's HTTP libraries
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
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
    implementation(libs.androidx.compose.material.icons.core)
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
    implementation(libs.commons.compress)
    // Optional cloud chat (PLAN 14, off by default)
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)
}
