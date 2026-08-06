plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.measure.feature.projects"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }

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
    api(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:units"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Pictures of the home screen. It is the first thing anyone sees and it had no
    // coverage at all, which is how a units toggle labelled "m" — turned into a lone
    // capital letter by the uppercase transform — reached a phone rather than CI.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.room.runtime)
    // Dispatchers.Main has no implementation on a bare JVM, and viewModelScope is built
    // on it — without this every coroutine the screen launches fails to dispatch.
    testImplementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.test.manifest)
}
