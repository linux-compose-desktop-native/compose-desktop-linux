# Compose Desktop for Linux/Native

Compose Multiplatform running as a native Linux executable — no JVM, no AWT.
SDL2 provides the window, input and OpenGL context; Skia renders through Ganesh.

```kotlin
// build.gradle.kts
plugins {
    id("dev.composenative.desktop") version "0.1.0"
}

// src/linuxX64Main/kotlin/Main.kt
fun main() = composeDesktopApplication(title = "My App") {
    MyContent()
}
```

The plugin configures the `linuxX64` target, the Compose compiler, the native
link flags and the library dependency. It is not sugar: Kotlin/Native does not
propagate a published cinterop KLIB's linker options to whoever links against
it, so without it an application fails with unresolved SDL2 and OpenGL symbols.

`composeDesktop { }` adjusts the defaults:

```kotlin
composeDesktop {
    executableName.set("my-app")   // defaults to the project name
    entryPoint.set("com.example.main")
    libraryVersion.set("0.1.0")
    addRepositories.set(false)     // if you declare repositories in settings.gradle.kts
}
```

## Status

Early. The rendering, input, clipboard, cursor and text-input paths work and are
covered by tests, but this is not a supported JetBrains configuration and the API
will change.

Currently `linuxX64` only.

## Why this exists

JetBrains publishes Skiko for Kotlin/Native on Apple targets and Compose Desktop
for the JVM, but nothing for Kotlin/Native on Linux. Making Compose run natively
here needs three pieces:

| Piece | Where |
|---|---|
| Skia bindings for `linuxX64` | [skiko fork](https://github.com/linux-compose-desktop-native/skiko) (`linux-native`) |
| Compose UI KLIBs for `linuxX64` | [compose-multiplatform-core fork](https://github.com/linux-compose-desktop-native/compose-multiplatform-core) (`linux-native`) |
| Window, GL context, scene hosting, event translation | this repository |

The forks are submodules under `third_party/`. Both carry a small number of
patches, kept as separate commits so they can be rebased onto upstream.

## Requirements

- JDK 21
- Kotlin 2.3.21 — **not negotiable**, KLIB ABI is locked to the compiler version
- SDL2, OpenGL, fontconfig and freetype development packages

```bash
# Arch
sudo pacman -S --needed sdl2 mesa libglvnd fontconfig freetype2 pkgconf
# Debian/Ubuntu
sudo apt install libsdl2-dev libgl-dev libfontconfig-dev libfreetype-dev pkg-config
```

## Building

```bash
git clone --recurse-submodules https://github.com/linux-compose-desktop-native/compose-desktop-linux
cd compose-desktop-linux

# Neither upstream ships Kotlin/Native Linux artifacts, so the forks must be
# built first. This takes a while the first time.
tools/publish-forks.sh

./gradlew :library:linuxTest
./gradlew :examples:demo:runDemo
```

Builds are memory-hungry; `tools/capped.sh` runs any command inside a 10 GB
cgroup if you need a ceiling:

```bash
tools/capped.sh ./gradlew :library:linuxTest
```

## Compositing foreign OpenGL content

Anything that renders with its own OpenGL commands — a video player, a game
view — can be composited into the scene by implementing `ExternalGlTexture`:

```kotlin
class MyRenderer : ExternalGlTexture {
    override val textureId: Int get() = texture
    override val width: Int get() = ...
    override val height: Int get() = ...

    override fun render(width: Int, height: Int) {
        // Draw into your own framebuffer, at the size this node was laid out to.
        // GL state need not be restored.
    }
}
```

Place it with the `ExternalTexture` composable. It is an ordinary layout node, so
it is sized and clipped like any other content, any number can coexist, and
Compose composites above or below it in tree order:

```kotlin
composeDesktopApplication(title = "Player") {
    Layout(contents = listOf(
        { ExternalTexture(video) },   // sized by the layout below
        { ExternalTexture(preview) }, // a second, independent source
        { Controls() },               // drawn over both
    )) { (v, p, c), constraints -> /* place them */ }
}
```

Skia *borrows* the texture and will never delete it; equally it must stay alive
while a frame referencing it is in flight. Skia's cached GL state is reset after
the foreign draws, so an implementation is free to leave state dirty — libmpv,
the case this was designed against, makes no guarantees about what it leaves
behind. A fixed baseline is also restored before each source draws (default
framebuffer bound; scissor, blend, depth and stencil disabled; texture unit 0
active), so one source cannot corrupt the next.

## Layout

```
library/         the host layer, published as a KLIB
gradle-plugin/   consumer build configuration and native link flags
examples/demo/   sample application
third_party/     the Skiko and Compose UI forks
tools/           build and publish scripts
```

See [DESIGN.md](DESIGN.md) for the architecture and the decisions behind it.

## Licence

Apache 2.0, matching Compose Multiplatform and Skiko.
