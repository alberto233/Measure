pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Measure"

// Pure-Kotlin core. These modules carry the measurement logic and are deliberately
// free of any Android dependency so they can be built and unit tested on the JVM.
include(":core:units")
include(":core:geometry")
include(":core:data")
include(":core:designsystem")
include(":core:export")

// ARCore session handling and AR rendering. Everything that imports com.google.ar
// lives here, so the rest of the app never depends on ARCore directly.
include(":ar")

// The AR capture screen.
include(":feature:capture")
include(":feature:projects")
include(":feature:editor")
include(":feature:export")

// First run, and the accuracy guidance. Its own module rather than a corner of
// :feature:projects because the capture screen needs to reopen it too, and a screen two
// features share is not owned by either of them.
include(":feature:onboarding")

include(":app")
