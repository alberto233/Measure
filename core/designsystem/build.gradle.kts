plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.traza.core.designsystem"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }

    // Robolectric needs the merged resources to resolve the geometry names in GeometryNames.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    api(project(":core:geometry"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    // Text and its typography plumbing. The controls live here now, so the design system
    // is the module that depends on Material rather than every feature doing it separately.
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
