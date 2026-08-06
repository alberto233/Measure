plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.measure.feature.editor"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric needs the merged resources and manifest to stand a real Android
    // environment up on the JVM. Without this the editor's UI tests fail at inflation
    // rather than at anything they are actually testing.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    implementation(project(":feature:export"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Interface tests, on the JVM. Robolectric rather than an emulator: an emulator job
    // takes minutes and fails for reasons unrelated to the code, and a test suite people
    // learn to re-run rather than read is one nobody reads.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.room.runtime)
    // Dispatchers.Main has no implementation on a bare JVM, and viewModelScope is built on
    // it — without this every coroutine the editor launches fails to dispatch.
    testImplementation(libs.kotlinx.coroutines.android)

    // Supplies the activity `createComposeRule` hosts the content in.
    debugImplementation(libs.compose.ui.test.manifest)
}
