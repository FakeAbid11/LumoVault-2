plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// Telegram API credentials are build inputs, never source code. Absent values stay absent:
// TELEGRAM_API_ID 0 / empty hash means "not configured in this build", which the app reports as a
// clear setup state instead of crashing or pretending to authenticate.
// Locally: -PTELEGRAM_API_ID=... -PTELEGRAM_API_HASH=...  In CI: repository secrets passed as properties.
val telegramApiId: Int = providers.gradleProperty("TELEGRAM_API_ID")
    .orElse(providers.environmentVariable("TELEGRAM_API_ID"))
    .getOrElse("0")
    .filter { it.isDigit() }
    .toIntOrNull()
    ?: 0

val telegramApiHash: String = providers.gradleProperty("TELEGRAM_API_HASH")
    .orElse(providers.environmentVariable("TELEGRAM_API_HASH"))
    .getOrElse("")
    // An api hash is hex; filtering keeps a stray quote from generating uncompilable BuildConfig.
    .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

// TDLib's generated Java interface and its native library are build inputs, not sources: CI copies
// Client.java and TdApi.java into src/tdlib/java/org/drinkless/tdlib/ and libtdjni.so into
// src/main/jniLibs/<abi>/ from the pinned build-tdlib.yml run, both verified against a pinned SHA-256.
// Without them the build fails on unresolved org.drinkless.tdlib references, which is the honest
// outcome — nothing here can compile against a TDLib that was not actually fetched. See README.
android {
    namespace = "com.lumovault.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lumovault.app"
        // MediaStore's unified Files collection, RELATIVE_PATH and IS_PENDING all date from API 29.
        // Supporting 26-28 would mean per-version column guesses that no build here could verify,
        // so the floor is the version whose column set is documented and stable.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // The auth failure path logs through android.util.Log — class name and mapped kind only.
            // Off-device there is no real Log, and AGP's default is to throw from every android.jar
            // method, which would turn a test of the error mapping into a test of the stub.
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("main") {
            // TDLib's generated Client.java and TdApi.java, dropped here by CI. An absolute path so
            // there is no question what the directory is relative to. Restricted to the debug variant
            // deliberately: nothing in Phase 1-4 builds a release, and a source set pointing at a
            // directory that exists only on the runner would break :assembleRelease with unresolved
            // references for a reason that has nothing to do with release code.
            java.directories.add("$projectDir/src/tdlib/java")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // The backup queue has to survive the screen being closed and the process being killed, which is
    // exactly the job WorkManager exists for. It is the only background mechanism used: no AlarmManager
    // schedule, no hand-rolled service, and no periodic poll — Phase 5 needs a queue that resumes, not
    // a timer that wakes.
    implementation(libs.androidx.work.runtime)

    // Offline country metadata: calling codes, example numbers, and E.164 normalisation. Hand-rolling
    // a ~240-row table that must agree with the parser is the worse trade; see CountryRepositoryImpl.
    implementation(libs.libphonenumber)

    // Thumbnail loading for content:// URIs: memory and disk caching, decode-to-target-size, and
    // cancellation with the composable that asked. coil-video adds MediaMetadataRetriever frame
    // decoding; both register themselves through ServiceLoader, so no ImageLoader setup is needed.
    // No network artifact is added: Phase 3 is entirely local.
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

// Versioned schema so the Phase 3/6 tables land as real migrations instead of destructive upsets.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
