// AGP 9.0 has built-in Kotlin support and enables it by default. The standalone
// org.jetbrains.kotlin.android plugin is not merely redundant now, it is rejected:
// applying it fails the build outright.
plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
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
    implementation(project(":feature:capture"))

    // Still needed here for the capability gate on the launch screen, which reports
    // ARCore availability and Depth support before any session is ever opened.
    implementation(libs.arcore)

    implementation(libs.androidx.activity.compose)
}
