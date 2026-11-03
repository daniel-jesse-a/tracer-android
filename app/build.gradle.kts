import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mapsKey: String = localProps.getProperty("MAPS_API_KEY") ?: System.getenv("MAPS_API_KEY") ?: ""
val mapBackend: String = (findProperty("tracer.mapBackend") as String?) ?: "maplibre"

android {
    namespace = "app.tracer"
    compileSdk = rootProject.extra["tracer.compileSdk"] as Int

    defaultConfig {
        applicationId = "app.tracer"
        minSdk = rootProject.extra["tracer.minSdk"] as Int
        targetSdk = rootProject.extra["tracer.targetSdk"] as Int
        // CI run number → monotonically increasing versionCode, so every build installs as an upgrade.
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = ciBuild
        versionName = "1.0.$ciBuild"
        // Pixel 6 Pro / Pixel 10 Pro / Galaxy S25 are arm64-only targets. For an x86_64 emulator build:
        //   ./gradlew :app:assembleDebug -Ptracer.emulator=true
        ndk {
            abiFilters += "arm64-v8a"
            if (findProperty("tracer.emulator") == "true") abiFilters += "x86_64"
        }
        testInstrumentationRunner = "app.tracer.HiltTestRunner"
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
        manifestPlaceholders["MAP_BACKEND"] = if (mapsKey.isBlank()) "maplibre" else mapBackend
        // Google Maps only when a key is present; MapLibre otherwise (or when forced).
        buildConfigField("String", "MAP_BACKEND", "\"${if (mapsKey.isBlank()) "maplibre" else mapBackend}\"")
        buildConfigField("String", "MAPLIBRE_STYLE_URL", "\"https://tiles.openfreemap.org/styles/liberty\"")
    }

    signingConfigs {
        // Fixed debug key (committed on purpose; debug-only) so CI-built APKs install over each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Store native libs uncompressed + page-aligned (required for 16 KB page-size devices, Android 15+).
        jniLibs.useLegacyPackaging = false
    }
    lint { warningsAsErrors = false; abortOnError = true; checkDependencies = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))
    implementation(project(":events"))
    implementation(project(":storage"))
    implementation(project(":collectors"))
    implementation(project(":probes"))
    implementation(project(":export"))
    implementation(project(":pairing"))
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
