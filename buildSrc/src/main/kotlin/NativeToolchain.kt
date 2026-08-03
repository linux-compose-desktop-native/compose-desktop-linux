import java.io.File

/**
 * Native compiler and linker flags for the Linux target.
 *
 * Shared by :library and the examples so they cannot drift apart. The published
 * Gradle plugin deliberately carries its own copy of this logic — it has to work
 * in a consumer's build, where none of this is on the classpath — so changes
 * here must be mirrored in ComposeDesktopPlugin.
 *
 * Nothing here hardcodes a filesystem layout. Arch, Debian/Ubuntu multiarch and
 * NixOS all put these files in different places.
 */
object NativeToolchain {

    private val packages = arrayOf("sdl2", "gl", "fontconfig", "freetype2")

    /**
     * The multiarch triplet on Debian-style layouts, or null where there is none.
     *
     * SDL2's SDL_config.h includes SDL2/_real_SDL_config.h, which those
     * distributions put under /usr/include/<triplet>, and sdl2.pc does not
     * mention it.
     */
    private val multiarch: String? by lazy { gcc("-print-multiarch") }

    val compilerOpts: List<String> by lazy {
        buildList {
            addAll(pkgConfig("--cflags"))
            add("-I/usr/include")
            multiarch?.let { add("-I/usr/include/$it") }
        }
    }

    val linkerOpts: List<String> by lazy {
        buildList {
            add("--allow-shlib-undefined")
            // Kotlin/Native's bundled ld.lld does not search the system library
            // paths, and pkg-config only reports them when they are non-default.
            add("-L/usr/lib")
            multiarch?.let { add("-L/usr/lib/$it") }
            addAll(pkgConfig("--libs"))
            // Kotlin/Native emits user linker options before the KLIB-embedded
            // archives, so without --no-as-needed the C++ runtime is discarded
            // before Skiko's bridge archive references it.
            add("--no-as-needed")
            add("-lstdc++")
            add("--as-needed")
            add("-ldl")
            add("-lpthread")
            // libstdc++ references __libc_single_threaded, which the sysroot
            // glibc that Kotlin/Native bundles does not define.
            gcc("-print-file-name=libc.so.6")
                ?.takeIf { it.startsWith("/") && File(it).exists() }
                ?.let { add(it) }
        }
    }

    private fun pkgConfig(vararg args: String): List<String> {
        val command = listOf("pkg-config") + args + packages
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) {
            "pkg-config could not find one of ${packages.joinToString(", ")}: $output\n" +
                "Install the SDL2, OpenGL, fontconfig and freetype development packages."
        }
        return output.split(" ").filter { it.isNotBlank() }
    }

    private fun gcc(argument: String): String? = runCatching {
        val process = ProcessBuilder("gcc", argument).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        output.takeIf { process.waitFor() == 0 && it.isNotBlank() }
    }.getOrNull()
}
