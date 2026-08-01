plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.measure.ar"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // api: ArUiState and the capture outcomes are built from these types, so anything
    // consuming this module reads them directly.
    api(project(":core:geometry"))

    implementation(libs.arcore)
    implementation(libs.kotlinx.coroutines.android)
}
