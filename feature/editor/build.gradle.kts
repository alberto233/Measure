plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.traza.feature.editor"
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

// Print why a test failed, into the log.
//
// Gradle's default is to name the failing test and put the reason in an HTML report — which
// on CI is a file nobody can open without downloading an artifact. The first run of these
// tests failed with "There were failing tests" and nothing else, which cost a round trip.
// Same reasoning as the "Surface compiler errors" step in the workflow.
tasks.withType<Test> {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
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

    // Screenshots from the same harness. The point is not catching pixel regressions —
    // it is that the screens in this project have never been looked at. Three interface
    // faults reached the user because they were reasoned about instead: text fields the
    // same colour as the panel behind them, a keyboard that threw the panel off screen,
    // and every control under the minimum touch size.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.room.runtime)
    // Dispatchers.Main has no implementation on a bare JVM, and viewModelScope is built on
    // it — without this every coroutine the editor launches fails to dispatch.
    testImplementation(libs.kotlinx.coroutines.android)

    // Supplies the activity `createComposeRule` hosts the content in.
    debugImplementation(libs.compose.ui.test.manifest)
}
