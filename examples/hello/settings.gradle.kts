// A standalone build on purpose.
//
// examples/demo is a subproject that depends on project(":library") for fast
// iteration; that path never exercises the published artifacts or the Gradle
// plugin. This one resolves everything the way a real consumer would, so it is
// the check that the published contract actually works.
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        // The Skiko and Compose UI forks. Published to the same remote as the
        // library once that is set up; until then they come from the local
        // repository that tools/publish-forks.sh writes.
        maven { url = uri("${rootDir}/../../build/maven-local") }
        mavenCentral()
        google()
    }
}

rootProject.name = "hello"
