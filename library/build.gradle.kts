plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    `maven-publish`
}

/** Version of the Skiko and Compose UI forks in third_party/. */
val forkVersion = "0.1.0-lcdn"

val composeForkModules = listOf(
    "org.jetbrains.compose.runtime:runtime-linuxx64",
    "org.jetbrains.compose.runtime:runtime-saveable-linuxx64",
    "org.jetbrains.compose.ui:ui-util-linuxx64",
    "org.jetbrains.compose.ui:ui-geometry-linuxx64",
    "org.jetbrains.compose.ui:ui-unit-linuxx64",
    "org.jetbrains.compose.ui:ui-graphics-linuxx64",
    "org.jetbrains.compose.ui:ui-text-linuxx64",
    "org.jetbrains.compose.ui:ui-backhandler-linuxx64",
    "org.jetbrains.compose.ui:ui-skiko-linuxx64",
    "org.jetbrains.compose.ui:ui-linuxx64",
    "org.jetbrains.androidx.navigationevent:navigationevent-linuxx64",
    "org.jetbrains.androidx.navigationevent:navigationevent-compose-linuxx64",
)

kotlin {
    val linux = linuxX64("linux")

    linux.compilations.getByName("main") {
        cinterops.create("desktop") {
            defFile(project.file("src/nativeInterop/cinterop/desktop.def"))
            packageName("dev.composenative.interop")
            compilerOpts(NativeToolchain.compilerOpts)
        }
    }

    linux.binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
        linkerOpts(NativeToolchain.linkerOpts)
    }

    sourceSets {
        linuxMain.dependencies {
            implementation(kotlin("stdlib"))

            // This graph is pinned by hand. The fork poms are not trustworthy —
            // ui-graphics and ui-skiko both declare a skiko version that is not
            // published for linuxX64, and ui's dependencyManagement contains
            // "unspecified" entries — so every fork dependency is non-transitive
            // and listed explicitly. Anything added to the forks must be mirrored
            // here, or it will be missing at link time.
            api("org.jetbrains.skiko:skiko-linuxx64:$forkVersion")
            for (module in composeForkModules) {
                api("$module:$forkVersion") { isTransitive = false }
            }

            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:atomicfu:0.28.0")
            implementation("org.jetbrains.compose.annotation-internal:annotation:1.10.0")
            implementation("org.jetbrains.compose.collection-internal:collection:1.10.0")
            implementation("androidx.compose.runtime:runtime-retain:1.12.0-beta01")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
            implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
            implementation("androidx.lifecycle:lifecycle-viewmodel:2.11.0")
            implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0")
            implementation("androidx.savedstate:savedstate-compose:1.4.0")
        }

        linuxTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

publishing {
    // The Gradle module is called :library, which would publish as
    // dev.composenative:library. Publish under the repository's name instead.
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactId.replaceFirst(Regex("^library"), "compose-desktop-linux")
    }

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
