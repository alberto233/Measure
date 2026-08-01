// Intentionally declares no plugins.
//
// The usual Android convention is to list every plugin here with `apply false`, putting
// them all on the root buildscript classpath. That does not work for this project: the
// Android Gradle Plugin resolves from Google's Maven host, which the development
// container cannot reach, so forcing AGP resolution at the root would stop the
// pure-Kotlin core building locally as well.
//
// Leaving the root bare gives each module its own plugin classloader scope. The core
// modules resolve only kotlin.jvm from Maven Central and never mention AGP; :app
// resolves AGP alone, because AGP 9 compiles Kotlin itself and rejects the standalone
// org.jetbrains.kotlin.android plugin.
