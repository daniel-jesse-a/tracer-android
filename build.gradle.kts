plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

/** Shared Android config, applied from each Android module via `tracerAndroid()`. */
extra["tracer.compileSdk"] = 36
extra["tracer.minSdk"] = 31
extra["tracer.targetSdk"] = 36

// Kotlin 2.2: opt into the future default for annotations on constructor properties (silences
// "applied to the value parameter only" for @ApplicationContext etc. and matches Kotlin 2.4 behaviour).
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}
