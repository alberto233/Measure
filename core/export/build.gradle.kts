plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Pure Kotlin on purpose. Every format here is text, so the whole of it can be
    // written and tested on the JVM — which matters more than usual for export, where a
    // single malformed character produces a file that opens as an error dialogue in
    // someone else's software rather than as a plan.
    api(project(":core:geometry"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
