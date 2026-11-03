plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.tracer.export"
    compileSdk = rootProject.extra["tracer.compileSdk"] as Int
    defaultConfig {
        minSdk = rootProject.extra["tracer.minSdk"] as Int
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { warningsAsErrors = false; abortOnError = true; checkDependencies = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(project(":storage"))
    implementation(libs.okhttp)
    // BundleServiceTest builds a TracerDatabase (supertype RoomDatabase) — :storage keeps Room as
    // `implementation`, so the test compile classpath needs it explicitly.
    testImplementation(libs.room.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
