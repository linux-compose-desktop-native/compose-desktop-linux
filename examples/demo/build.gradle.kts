import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
}

/**
 * This module configures itself by hand on purpose.
 *
 * It builds against the library as a project dependency, before the Gradle
 * plugin exists in a consumable form, so it duplicates what the plugin does.
 * The clean-room consumer check in the README is what verifies the plugin path;
 * this only verifies the library.
 */
fun pkgConfig(vararg args: String): List<String> {
    val process = ProcessBuilder(listOf("pkg-config") + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0) { "pkg-config ${args.joinToString(" ")} failed: $output" }
    return output.split(" ").filter { it.isNotBlank() }
}

@OptIn(KotlinNativeCacheApi::class)
kotlin {
    linuxX64("linux") {
        binaries {
            executable {
                baseName = "compose-desktop-demo"
                entryPoint = "dev.composenative.demo.main"
                disableNativeCache(
                    version = DisableCacheInKotlinVersion.`2_3_21`,
                    reason = "Skiko's C++ runtime dependency must be linked after its object archive",
                )
                linkerOpts(
                    buildList {
                        add("--allow-shlib-undefined")
                        addAll(pkgConfig("--libs", "sdl2", "gl", "fontconfig", "freetype2"))
                        add("--no-as-needed")
                        add("-lstdc++")
                        add("--as-needed")
                        add("-ldl")
                        add("-lpthread")
                    }
                )
            }
        }
    }

    sourceSets {
        linuxMain.dependencies {
            implementation(project(":library"))
        }
    }
}

tasks.register<Exec>("runDemo") {
    group = "application"
    description = "Builds and runs the demo application."
    dependsOn("linkDebugExecutableLinux")
    commandLine(
        layout.buildDirectory.file("bin/linux/debugExecutable/compose-desktop-demo.kexe")
            .get().asFile.absolutePath
    )
}
