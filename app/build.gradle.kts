// AGP 9.0 has built-in Kotlin support and enables it by default. The standalone
// org.jetbrains.kotlin.android plugin is not merely redundant now, it is rejected:
// applying it fails the build outright.
import java.util.Properties

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

/**
 * The upload key, from anywhere except this repository.
 *
 * A `keystore.properties` at the repository root for a person's own machine, environment
 * variables for CI, and nothing at all for everyone else — which is the case that has to keep
 * working, because a signed release build is a thing exactly one person can produce and every
 * other build in the world must not fail for want of it.
 *
 * Both sources are read here rather than in the `signingConfigs` block so that the *absence*
 * of a key is a value this file can branch on, instead of an exception thrown deep inside
 * AGP at execution time.
 */
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingSecret(property: String, variable: String): String? =
    (keystore.getProperty(property) ?: System.getenv(variable))?.takeIf { it.isNotBlank() }

val uploadKeystore = signingSecret("storeFile", "TRAZA_KEYSTORE")
val uploadStorePassword = signingSecret("storePassword", "TRAZA_KEYSTORE_PASSWORD")
val uploadKeyAlias = signingSecret("keyAlias", "TRAZA_KEY_ALIAS")
val uploadKeyPassword = signingSecret("keyPassword", "TRAZA_KEY_PASSWORD")

val canSignRelease = uploadKeystore != null &&
    uploadStorePassword != null &&
    uploadKeyAlias != null &&
    uploadKeyPassword != null

android {
    namespace = "com.traza.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.traza.app"
        minSdk = 26
        targetSdk = 36
        // Bumped whenever a build goes out for testing. Android will not install a
        // lower code over a higher one, and a version that never changes gives the user
        // no way to tell which build is on the phone.
        //
        // Still a dev sequence. The first public build wants a deliberate `versionName`
        // — see `docs/STORE_LISTING.md` §9 — but the *code* keeps climbing from here
        // rather than restarting at 1, because a lower code will not install over the
        // builds already on test phones.
        versionCode = 9
        versionName = "0.2.0"
    }

    androidResources {
        // Ship only what is written here. AndroidX arrives translated into eighty-odd
        // languages, and without this every one of them is packaged — which inflates the
        // APK and, worse, makes a phone set to French show a French "Cancel" beside an
        // English sentence.
        localeFilters += listOf("en", "es")
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
        if (canSignRelease) {
            create("release") {
                storeFile = file(uploadKeystore!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false

            // Null when no key is configured, which leaves the APK unsigned rather than
            // silently falling back to the debug key. An unsigned release fails at install
            // with a message about signing; a debug-signed one installs perfectly, runs
            // perfectly, and is rejected by Play months later with the plans of everyone
            // who sideloaded it now locked to a key that cannot be used again.
            signingConfig = signingConfigs.findByName("release")
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
    // For CalibrationStore. Arrives transitively through :feature:editor's api() today, but
    // depending on that means this module breaks the day the editor narrows its own
    // dependency for reasons of its own.
    implementation(project(":core:data"))
    // The device check is Compose now, and uses the same tokens and controls as every
    // other screen rather than the six duplicated Color.parseColor constants it had.
    implementation(project(":core:designsystem"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:projects"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:export"))
    implementation(project(":feature:onboarding"))

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
