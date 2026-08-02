# Compose Desktop for Linux/Native

Compose Multiplatform running as a native Linux executable — no JVM, no AWT.
SDL2 provides the window, input and OpenGL context; Skia renders through Ganesh.

```kotlin
fun main() = composeDesktopApplication(title = "My App") {
    MyContent()
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
        // Draw into your own framebuffer. GL state need not be restored.
    }
}

composeDesktopApplication(externalTexture = MyRenderer()) { MyContent() }
```

Skia *borrows* the texture and will never delete it; equally it must stay alive
while a frame referencing it is in flight. Skia's cached GL state is reset after
each foreign draw, so the implementation is free to leave state dirty — libmpv,
the case this was designed against, makes no guarantees about what it leaves
behind.

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
