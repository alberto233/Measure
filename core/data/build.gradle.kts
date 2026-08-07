plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.measure.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

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

// MigrationTestHelper reads the old schemas from assets, so the exported JSON has to be on
// the unit test's asset path. Pointing at `schemas` rather than copying means the test can
// only ever run against the files that are actually checked in.
//
// Configured through the typed extension rather than inside `android { }`: the Kotlin DSL
// accessor for a library's `sourceSets` still resolves to AGP's legacy interface, which
// AGP 9's implementation no longer implements, so the generated accessor fails on a cast.
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
}

ksp {
    // Checked in, so migrations can be written and tested against the real old schema
    // rather than against someone's memory of it.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // api: the repository speaks in geometry and unit types, so callers need them.
    api(project(":core:geometry"))

    implementation(libs.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.room.compiler)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // The migration test. Robolectric because MigrationTestHelper needs an Instrumentation
    // and the generated MeasureDatabase_Impl needs an Android runtime to load at all — but
    // Robolectric supplies both on the JVM, so this stays a unit test rather than becoming
    // an emulator job nobody waits for.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testRuntimeOnly(libs.junit.vintage.engine)
}

// Print why a test failed, into the log — the reason the workflow can report a failure
// without anyone downloading an HTML artifact to read it.
tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
