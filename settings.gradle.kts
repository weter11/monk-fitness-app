pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // The app applies the Kotlin compiler through AGP; the exporter needs the JVM plugin, and
        // pinning it here keeps one version for both halves of the build.
        id("org.jetbrains.kotlin.jvm") version "2.2.10"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Monk Fitness App"
include(":app")
include(":tools:anatomy-export")
