# Compose Native Desktop Design

## Status

Accepted architecture decisions for the initial implementation.

The project will build a native desktop host for Compose Multiplatform rather
than use the JVM-based Compose Desktop runtime. The first supported target is
Kotlin/Native `linuxX64`.

## Goals

- Produce a native executable with no JVM dependency.
- Reuse Compose Runtime and Compose UI instead of creating a new UI toolkit.
- Render Compose through Skia using Kotlin/Native bindings.
- Use SDL2 for portable window, input, and graphics-context integration.
- Support GPU-composited content from foreign renderers, without depending on
  any particular one.
- Keep platform hosting, graphics bindings, and Compose integration separated
  so each can evolve independently.

## Non-goals

- Reimplementing Compose drawing with Cairo or another software renderer.
- Maintaining a CPU-rendered full-window buffer as the primary rendering path.
- Reimplementing Skia's API directly when Skiko code can be ported or reused.
- Matching all first-party desktop functionality in the first milestone.
- Treating SDL2 as part of the public Compose API.

## Architecture

```text
Application composables
        |
        v
Compose Runtime and Compose UI
        |
        v
Linux/Native Compose platform implementation
        |
        v
Kotlin/Native Skiko/Skia bindings
        |
        v
Skia Ganesh OpenGL renderer
        |
        v
SDL2 window and OpenGL context
```

Foreign GPU content follows a parallel path into the same compositor:

```text
Foreign OpenGL renderer (libmpv, a game view, ...)
        |
        v
Off-screen OpenGL framebuffer and texture
        |
        v
Non-owning Skia backend texture / image
        |
        v
Compose Canvas and final Skia surface
```

## Decisions

### Kotlin/Native is the application runtime

The application will use a Kotlin Multiplatform `linuxX64` executable target.
Compose Runtime, application state, platform integration, and native-library
interop will execute under Kotlin/Native. A JVM process and AWT/Swing are not
part of the architecture.

### Skia is the Compose rendering backend

Skia is the authoritative graphics implementation. The project will preserve
the graphics model already used by Compose Multiplatform rather than translate
Compose drawing operations to Cairo.

This aligns the backend with the existing Compose Desktop and native Apple
implementations and avoids a full-window CPU-to-GPU upload each frame.

### Port Skiko instead of creating an unrelated Skia wrapper

The preferred implementation is a `linuxX64` Kotlin/Native port of the relevant
Skiko bindings and native glue. Existing Skiko API shapes, ownership rules, and
Compose adapters should be retained where practical.

Skia's C++ ABI will not be exposed directly to Kotlin/Native. Any missing
bindings will be added through a stable C-compatible native bridge, following
the approach used by Skiko.

The port only needs the surface required by Compose and this application at
first. It does not need to expose every Skia API before the first usable build.

### Ganesh with OpenGL is the initial GPU backend

The initial renderer will use Skia Ganesh on an SDL-created OpenGL context.
This gives Skia and any foreign renderer a shared, mature graphics API and
minimizes the
amount of platform-specific context code.

Graphite and Vulkan are deferred. The module boundaries must not prevent a
future renderer from replacing Ganesh/OpenGL.

### SDL2 is the native desktop host

SDL2 will initially provide:

- Window creation, resize, focus, and lifecycle events
- OpenGL context creation and buffer swapping
- Pointer, keyboard, and text-input events
- DPI and drawable-size information
- Clipboard and cursor integration
- Frame timing and wake-up events

SDL events will be translated into Compose platform events. SDL types must not
leak into application composables or the public media API.

SDL2 is a pragmatic replaceable host, not an assertion that a hypothetical
first-party JetBrains backend would choose SDL. Direct Wayland/X11 or other
native hosts can be introduced later behind the same platform boundary.

### Foreign renderers composite through an external OpenGL texture

The project does not depend on any specific media or GPU library. Instead it
exposes one seam, `ExternalGlTexture`, which any renderer that draws with its
own OpenGL commands can implement: a video player, a game view, or another
GPU-accelerated embed.

The implementation renders into a framebuffer and texture that it owns. That
texture is wrapped as a *non-owning* Skia backend texture and drawn by the
`ExternalTexture` composable, which is an ordinary layout node. Textures are
therefore sized, clipped and transformed by layout, composited in tree order,
and any number can coexist in one scene. Compose content can be placed above,
below, clipped around, and transformed with them.

Foreign OpenGL work cannot happen while Skia is drawing, so it does not happen
inside the composable. Composables register their source during composition; the
renderer draws every registered source after layout but before the scene is
drawn, then resets Skia's cached GL state once. Running layout first means each
source draws at the size it was actually laid out to, with no frame of lag.

A fixed GL baseline is restored before each source draws, so that one source
cannot corrupt the next. Without it a source that leaves the scissor test
enabled silently clips the following source's clear.

libmpv is the reference case this was designed against — using
`MPV_RENDER_API_TYPE_OPENGL` with addresses resolved through
`SDL_GL_GetProcAddress` — but it is deliberately not a dependency of this
project. Consumers add it themselves; see "Integrating a foreign renderer".

The integration must define explicitly:

- Texture and framebuffer ownership
- Top-left versus bottom-left image origin
- Color type, color space, and premultiplied-alpha behavior
- Resize and resource-recreation behavior
- OpenGL state restoration between the foreign renderer and Skia
- Flush and synchronization points
- The single UI/render thread that owns the OpenGL context

Neither Skia nor the foreign renderer may delete graphics resources owned by
the other. Skia borrows the texture; it never adopts it.

### One render thread owns Compose, Skia, and OpenGL

The initial implementation will keep SDL event dispatch, Compose scene
rendering, Skia submission, and foreign rendering on one UI/render thread.
Other
threads may perform I/O or receive media notifications, but they must hand work
back to the render thread before touching the graphics context or Compose UI
state that requires thread confinement.

### Compose platform support is a distinct module

Linux/Native Compose hosting will be isolated from application code. It will
eventually cover:

- Scene creation and frame-clock scheduling
- Density and window metrics
- Pointer, scroll, keyboard, focus, and text-input translation
- Clipboard and cursors
- Font discovery and resource loading
- IME integration
- Accessibility

The first milestone may implement only the subset needed for a functional
window containing text and controls. Missing capabilities must be
tracked rather than silently emulated in application code.

## Intended module boundaries

```text
library/                   Linux/Native Compose hosting: SDL window, events,
                           clipboard, cursors, GL context, and Skia renderer
gradle-plugin/             Consumer build configuration and native link flags
examples/demo/             Sample application, consuming the published artifacts
third_party/skiko/         Kotlin/Native Skiko and Skia bindings (fork)
third_party/               Compose UI Linux/Native source (fork)
  compose-multiplatform-core/
```

Exact Gradle module names may change, but these ownership boundaries are part
of the design. The distinction that matters is that hosting is a *library* with
a published artifact, not application code: an application must be able to
depend on it without vendoring SDL, Skiko, or Compose fork sources.

Current state: hosting and the sample application are still fused in `app/`,
and nothing is published. Splitting them is the next structural change.

## Frame lifecycle

The expected frame sequence is:

1. Poll and translate SDL events.
2. Advance the Compose frame clock and apply pending recompositions.
3. Let any external renderer draw into its own off-screen OpenGL target.
4. Reset Skia's cached OpenGL state.
5. Render the Compose scene, including wrapped external textures, into the Skia
   surface associated with the SDL window framebuffer.
6. Flush and submit Skia work.
7. Swap the SDL window buffers.
9. Sleep or wait until an input, media, or Compose invalidation requires the
   next frame.

Rendering should be invalidation-driven where possible rather than permanently
busy-looping.

## Delivery sequence

1. Build a Kotlin/Native executable that opens an SDL2 OpenGL window.
2. Build and call the minimal Linux/Native Skia/Skiko bindings.
3. Render Skia primitives and text to the SDL window framebuffer.
4. Host a minimal Compose scene and render an interactive control.
5. Complete essential SDL-to-Compose input, density, clipboard, and cursor
   integration.
6. Composite a foreign OpenGL texture into the Compose scene through Skia.
7. Add resizing and lifecycle handling.
8. Expand platform completeness, including IME and accessibility.

## Known risks

- Official Skiko currently does not publish a Kotlin/Native Linux target.
- Official Compose Desktop for Linux targets the JVM, so Compose UI native
  artifacts or source sets may require a maintained fork.
- Compose platform internals can change between releases.
- Building and packaging Skia substantially increases build complexity and
  artifact size.
- OpenGL state sharing between Skia and a foreign renderer requires careful
  isolation.
- Text input, accessibility, and native font behavior are larger platform
  projects than basic rendering and pointer input.

Dependency versions should be pinned together and upgraded deliberately. The
project should minimize patches to upstream Compose and Skiko and keep them in
clearly separated modules to make rebasing practical.

## Deferred decisions

- Exact Kotlin, Compose Multiplatform, Skiko, Skia, and SDL2 versions
- Static versus dynamic packaging of Skia and native dependencies
- X11/Wayland backend preferences passed to SDL
- Public composable API for external textures
- Whether multiple windows share GPU resources
- Graphite/Vulkan support
- Windows and macOS Kotlin/Native targets
- Accessibility implementation strategy

