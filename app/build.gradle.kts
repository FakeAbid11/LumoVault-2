plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lumovault.lumovault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lumovault.lumovault"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "TELEGRAM_API_ID", "\"${project.findProperty("TELEGRAM_API_ID") ?: "0"}\"")
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${project.findProperty("TELEGRAM_API_HASH") ?: ""}\"")
        buildConfigField("String", "SENTRY_DSN", "\"${project.findProperty("SENTRY_DSN") ?: ""}\"")

        // TDLib ships prebuilt per-ABI; don't let Gradle package others.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            // Prebuilt official TDLib JNI library + generated Client.java/TdApi.java,
            // produced by .github/workflows/tdlib-build.yml and committed under
            // tdlib-prebuilt/ (absent until that workflow runs once).
            java.srcDirs("../tdlib-prebuilt/src/main/java")
            // The prebuilt tree also carries tdlib-prebuilt/build-<abi>-Java/
            // duplicates of the .so, which are NOT valid jniLibs ABI directory
            // names and break mergeDebugNativeLibs. Copy only the real ABIs into
            // a generated jniLibs root instead of pointing srcDirs at the tree.
            jniLibs.srcDir(layout.buildDirectory.dir("generated/tdlibJniLibs"))
        }
    }
}

// Populates the generated jniLibs root from tdlib-prebuilt/<abi>/libtdjni.so.
tasks.register<Copy>("installTdlibPrebuiltJniLibs") {
    from("../tdlib-prebuilt")
    into(layout.buildDirectory.dir("generated/tdlibJniLibs"))
    include("arm64-v8a/**", "x86_64/**")
}
// mergeNativeLibs consumes the jniLibs source dirs, so it must not run before
// they are populated.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
    .configureEach { dependsOn("installTdlibPrebuiltJniLibs") }

android {

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

ksp {
    // Room writes its schema JSON here so future migrations can be tested.
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // MediaStore / Photo picker
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Maps
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Accompanist
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Sentry
    implementation("io.sentry:sentry-android:7.19.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
