package dev.composenative.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable

/**
 * Configures a project to build a Compose application as a native Linux executable.
 *
 * This plugin is not sugar. Kotlin/Native does not propagate the linker options of
 * a published cinterop KLIB to whoever links against it, so an application
 * depending on the library alone fails with unresolved SDL2 and OpenGL symbols.
 * Something has to put those flags on the consumer's own binary, and that has to
 * be a plugin.
 *
 * It also resolves those flags through pkg-config rather than hardcoding paths,
 * so the same build works on Arch, Debian/Ubuntu multiarch and NixOS.
 */
class ComposeDesktopPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("composeDesktop", ComposeDesktopExtension::class.java)
        extension.libraryVersion.convention(DEFAULT_LIBRARY_VERSION)
        extension.entryPoint.convention("main")
        extension.executableName.convention(project.name)
        extension.addRepositories.convention(true)

        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        // The target has to exist during configuration: the Kotlin plugin registers
        // its compile and link tasks from it, and a target created in afterEvaluate
        // arrives too late for them to be found.
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val linkerOpts = nativeLinkerOptions()
        var executable: Executable? = null
        kotlin.linuxX64 { target ->
            target.binaries.executable { binary ->
                binary.linkerOpts(linkerOpts)
                executable = binary
            }
        }

        // Values from the composeDesktop { } block are only final once the build
        // script has run, so anything reading them waits until then. Dependencies
        // and repositories resolve later still, so this is early enough.
        project.afterEvaluate {
            verifyKotlinVersion(project)
            if (extension.addRepositories.get()) addRepositories(project)

            executable?.apply {
                baseName = extension.executableName.get()
                entryPoint = extension.entryPoint.get()
            }
            kotlin.sourceSets.getByName("linuxX64Main").dependencies {
                implementation("dev.composenative:compose-desktop-linux:${extension.libraryVersion.get()}")
            }
        }
    }

    /**
     * The KLIBs are compiled by a specific Kotlin/Native compiler and can only be
     * read by that same version. A mismatch otherwise surfaces as an unreadable
     * library or a confusing link failure, so fail with the actual reason.
     */
    private fun verifyKotlinVersion(project: Project) {
        val actual = project.getKotlinPluginVersion()
        if (actual != REQUIRED_KOTLIN_VERSION) {
            throw GradleException(
                """
                Compose Desktop for Linux/Native requires Kotlin $REQUIRED_KOTLIN_VERSION, but this build uses $actual.

                The Compose and Skia KLIBs are compiled by a specific Kotlin/Native
                compiler and cannot be read by a different one. Pin the Kotlin plugin
                to $REQUIRED_KOTLIN_VERSION.
                """.trimIndent()
            )
        }
    }

    private fun addRepositories(project: Project) {
        project.repositories.maven { repository ->
            repository.name = "composeDesktopLinux"
            repository.setUrl(PACKAGES_URL)
            repository.credentials { credentials ->
                credentials.username = project.findProperty("gpr.user") as String?
                    ?: System.getenv("GITHUB_ACTOR")
                credentials.password = project.findProperty("gpr.key") as String?
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
        project.repositories.mavenCentral()
        project.repositories.google()
    }

    /**
     * Asks pkg-config for the native flags, and explains what to install if the
     * development packages are missing rather than failing at link time.
     */
    private fun pkgConfig(vararg args: String): List<String> {
        val process = try {
            ProcessBuilder(listOf("pkg-config") + args).redirectErrorStream(true).start()
        } catch (cause: Exception) {
            throw GradleException(
                "pkg-config is not installed, and is required to locate SDL2 and OpenGL.",
                cause,
            )
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) {
            throw GradleException(
                """
                pkg-config could not find one of: ${NATIVE_PACKAGES.joinToString(", ")}

                $output

                Install the development packages:
                  Arch          sudo pacman -S --needed sdl2 mesa libglvnd fontconfig freetype2 pkgconf
                  Debian/Ubuntu sudo apt install libsdl2-dev libgl-dev libfontconfig-dev libfreetype-dev pkg-config
                  Fedora        sudo dnf install SDL2-devel mesa-libGL-devel fontconfig-devel freetype-devel pkgconf
                """.trimIndent()
            )
        }
        return output.split(" ").filter { it.isNotBlank() }
    }

    /**
     * Flags needed to link an executable against the library.
     *
     * `--no-as-needed` around `-lstdc++` is load-bearing: Kotlin/Native emits user
     * linker options before the KLIB-embedded archives, so the C++ runtime would
     * otherwise be discarded before Skiko's bridge archive references it.
     */
    private fun nativeLinkerOptions(): List<String> = buildList {
        add("--allow-shlib-undefined")
        addAll(pkgConfig("--libs", *NATIVE_PACKAGES))
        add("--no-as-needed")
        add("-lstdc++")
        add("--as-needed")
        add("-ldl")
        add("-lpthread")
    }

    private companion object {
        const val REQUIRED_KOTLIN_VERSION = "2.3.21"
        const val DEFAULT_LIBRARY_VERSION = "0.1.0"
        const val PACKAGES_URL =
            "https://maven.pkg.github.com/linux-compose-desktop-native/compose-desktop-linux"
        val NATIVE_PACKAGES = arrayOf("sdl2", "gl", "fontconfig", "freetype2")
    }
}

/** Settings for [ComposeDesktopPlugin]; reached through the `composeDesktop { }` block. */
interface ComposeDesktopExtension {
    /** Version of the Compose Desktop for Linux/Native library to build against. */
    val libraryVersion: Property<String>

    /** Fully qualified entry point, defaulting to `main`. */
    val entryPoint: Property<String>

    /** Base name of the produced executable, defaulting to the project name. */
    val executableName: Property<String>

    /**
     * Whether to declare the repositories the library is published to.
     *
     * Turn this off if the build declares repositories centrally in
     * `settings.gradle.kts`, which is required when `repositoriesMode` is set to
     * `FAIL_ON_PROJECT_REPOS`.
     */
    val addRepositories: Property<Boolean>
}
