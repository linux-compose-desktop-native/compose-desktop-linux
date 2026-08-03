plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

// Deliberately kotlin("jvm") rather than `kotlin-dsl`: that plugin pins the Kotlin
// version embedded in Gradle, which would clash with the 2.3.21 the rest of this
// build uses. Nothing here needs the kotlin-dsl accessors.

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.21")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

gradlePlugin {
    website.set("https://github.com/linux-compose-desktop-native/compose-desktop-linux")
    vcsUrl.set("https://github.com/linux-compose-desktop-native/compose-desktop-linux.git")

    plugins {
        create("composeDesktop") {
            id = "dev.composenative.desktop"
            implementationClass = "dev.composenative.gradle.ComposeDesktopPlugin"
            displayName = "Compose Desktop for Linux/Native"
            description = "Builds Compose Multiplatform applications as native Linux " +
                "executables, with no JVM, using SDL2 and Skia."
            tags.set(listOf("kotlin", "kotlin-native", "compose", "linux", "desktop"))
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/linux-compose-desktop-native/compose-desktop-linux")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}
