plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.measure.feature.capture"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    // Robolectric needs the merged resources and manifest to stand a real Android
    // environment up on the JVM.
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
    api(project(":ar"))
    api(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:units"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Pictures of the overlays. Everything here has to stay legible over a live camera
    // image of unknown colour and brightness, which is one of M10a's five constraints and
    // the only one that cannot be checked by reading the code.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    debugImplementation(libs.compose.ui.test.manifest)
}
