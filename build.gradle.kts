// Kotlin's JVM and Android plugins ship in the same artifact, so both markers must be
// declared here. Declaring only one puts that jar on the classpath at an unversioned
// coordinate, and a subproject asking for the other by version then fails compatibility
// checking.
//
// The Android Gradle Plugin is deliberately *not* declared here. It resolves from
// Google's Maven host, which the development container cannot reach, and keeping it
// scoped to :app lets the pure-Kotlin core still build and test locally via
// configuration-on-demand.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
}
