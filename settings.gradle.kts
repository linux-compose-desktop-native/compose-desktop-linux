pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // The Skiko and Compose UI forks in third_party/ are published here by
        // tools/publish-forks.sh. Neither upstream ships Kotlin/Native Linux
        // artifacts, so this repository has to come first.
        maven {
            name = "forks"
            url = uri(rootDir.resolve("build/maven-local"))
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-desktop-linux"

include(":library")
include(":examples:demo")
