plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Isolated spike: a second, standalone APK that proves the Filament runtime against the real
// Monk motion engine. It shares the engine *sources* rather than depending on :app, because :app
// is an application module and promoting the engine to a library is out of scope for this stage.
android {
    namespace = "com.monkfitness.spike"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.monkfitness.spike"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-spike"
        ndk {
            abiFilters += "arm64-v8a"
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

    // The spike reads the engine straight from the app tree; the engine is never modified here.
    sourceSets["main"].java.srcDir("../app/src/main/java/com/monkfitness/app/animation")
    sourceSets["main"].java.srcDir("../app/src/main/java/com/monkfitness/app/poses")

    // glTF assets are memory-mapped by gltfio, so they must stay uncompressed.
    androidResources {
        noCompress += listOf("glb")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.material3)

    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.google.android.filament:gltfio-android:1.75.1")
    implementation("com.google.android.filament:filament-utils-android:1.75.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation(libs.androidx.ui.tooling)
}
