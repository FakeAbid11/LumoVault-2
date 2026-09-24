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

android {
    namespace = "com.lumovault.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lumovault.app"
        minSdk = 26
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

    // Offline country metadata: calling codes, example numbers, and E.164 normalisation. Hand-rolling
    // a ~240-row table that must agree with the parser is the worse trade; see CountryRepositoryImpl.
    implementation(libs.libphonenumber)
    // TDLib's client interface is JSON. Parsed via the JsonElement API, so no compiler plugin is
    // needed and the mapping is testable off-device.
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

// Versioned schema so the Phase 3/6 tables land as real migrations instead of destructive upsets.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
