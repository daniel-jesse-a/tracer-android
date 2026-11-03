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
        google()
        mavenCentral()
    }
}

rootProject.name = "Tracer"

// Pure Kotlin/JVM — no Android dependency, unit-testable anywhere.
include(":core")
include(":events")

// Android libraries
include(":storage")
include(":collectors")
include(":probes")
include(":export")
include(":pairing")
include(":ui")

// Application (RunService, DI graph, MainActivity)
include(":app")
