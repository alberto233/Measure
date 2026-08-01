plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api, not implementation: Length and Area appear in this module's public API
    // (RoomSolution, MeasuredSegment), so consumers need them on their compile classpath.
    api(project(":core:units"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
