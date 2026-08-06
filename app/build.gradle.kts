// AGP 9.0 has built-in Kotlin support and enables it by default. The standalone
// org.jetbrains.kotlin.android plugin is not merely redundant now, it is rejected:
// applying it fails the build outright.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.roborazzi)
    // Required in *every* module containing a @Composable, including this one, whose
    // only Compose code is a single setContent call. Without it the Kotlin compiler
    // still type-checks the composable lambda but emits it untransformed, as a plain
    // Function0 rather than the Function2 the Compose runtime expects — which links to
    // nothing and throws NoSuchMethodError the moment the activity starts. Nothing
    // fails at build time, so the rule is: composables anywhere, plugin here.
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.measure.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.measure.app"
        minSdk = 26
        targetSdk = 36
        // Bumped whenever a build goes out for testing. Android will not install a
        // lower code over a higher one, and a version that never changes gives the user
        // no way to tell which build is on the phone.
        versionCode = 3
        versionName = "0.1.2"
    }

    // A debug keystore committed to the repository, so every build — CI, local, anyone's
    // machine — is signed with the same key.
    //
    // Without this AGP signs with whatever throwaway key happens to be in
    // ~/.android/debug.keystore on the build machine. GitHub's runners are ephemeral, so
    // when the runner image rolled the key changed underneath us, and Android refused to
    // update an installed app whose signature no longer matched: "App not installed",
    // with no indication of why. The only recovery is uninstalling, which takes the
    // user's saved plans with it.
    //
    // Committing a *debug* keystore is safe and is the standard fix. Its password is the
    // published default, it grants nothing, and Play refuses debug-signed uploads
    // outright. A release key would never go anywhere near version control.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
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

// Top-level rather than inside android { kotlinOptions }, which AGP 9 removed. The
// extension is contributed by AGP itself now.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:units"))
    implementation(project(":core:geometry"))
    // The device check is Compose now, and uses the same tokens and controls as every
    // other screen rather than the six duplicated Color.parseColor constants it had.
    implementation(project(":core:designsystem"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:projects"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:export"))

    // Still needed here for the capability gate on the launch screen, which reports
    // ARCore availability and Depth support before any session is ever opened.
    implementation(libs.arcore)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    // Pictures of the device check. It is the one screen a user meets before anything
    // works, it has five distinct states, and four of them only occur on hardware this
    // repository has no access to — which is exactly the case for rendering them here.
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
