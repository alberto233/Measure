// Intentionally declares no plugins.
//
// The usual Android convention is to list every plugin here with `apply false`, which
// puts them all on the root buildscript classpath. That does not work for this project:
// the Android Gradle Plugin resolves from Google's Maven host, which the development
// container cannot reach, so anything forcing AGP resolution at the root would stop the
// pure-Kotlin core building locally too.
//
// Two arrangements were tried and rejected, both recorded here so they are not
// reattempted:
//
//   1. kotlin.jvm alone at the root — Kotlin's JVM and Android plugins ship in one
//      artifact, so this put that jar on the classpath unversioned and :app asking for
//      kotlin.android by version failed compatibility checking.
//   2. kotlin.android at the root with AGP left in :app — the Kotlin Android plugin then
//      loads from the root classloader while AGP loads from :app's child classloader, and
//      a parent cannot see a child's classes. It failed with
//      ClassNotFoundException: com.android.build.gradle.api.BaseVariant.
//
// Leaving the root bare gives every module its own plugin classloader scope. :app gets
// AGP and kotlin.android together in one scope, so KGP can see AGP; the core modules
// resolve only kotlin.jvm from Maven Central and never mention AGP at all.
