package dev.composenative

import androidx.compose.runtime.Composable
import cnames.structs.SDL_Window
import dev.composenative.interop.*
import kotlinx.cinterop.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers

/**
 * Opens a window and runs [content] in it until the window is closed.
 *
 * This owns everything below the application: SDL initialisation, the window and
 * its OpenGL context, the Skia renderer, the Compose scene, event translation,
 * and teardown. It blocks the calling thread, which becomes the single
 * UI/render thread that owns the OpenGL context.
 *
 * ```
 * fun main() = composeDesktopApplication(title = "My App") {
 *     MyContent()
 * }
 * ```
 *
 * Foreign OpenGL content is placed in the scene with the [ExternalTexture]
 * composable rather than passed in here, so it can be positioned and layered like
 * any other content.
 */
@OptIn(ExperimentalForeignApi::class)
fun composeDesktopApplication(
    title: String = "Compose Desktop",
    width: Int = 1280,
    height: Int = 720,
    resizable: Boolean = true,
    content: @Composable () -> Unit,
) {
    checkSdl(SDL_Init(SDL_INIT_VIDEO or SDL_INIT_EVENTS)) { "SDL_Init" }

    try {
        configureOpenGl()
        var flags = SDL_WINDOW_OPENGL or SDL_WINDOW_ALLOW_HIGHDPI
        if (resizable) flags = flags or SDL_WINDOW_RESIZABLE
        val window = SDL_CreateWindow(
            title,
            SDL_WINDOWPOS_CENTERED.toInt(),
            SDL_WINDOWPOS_CENTERED.toInt(),
            width,
            height,
            flags,
        ) ?: error("SDL_CreateWindow failed: ${SDL_GetError()?.toKString()}")

        try {
            val context = SDL_GL_CreateContext(window)
                ?: error("SDL_GL_CreateContext failed: ${SDL_GetError()?.toKString()}")
            try {
                checkSdl(SDL_GL_MakeCurrent(window, context)) { "SDL_GL_MakeCurrent" }
                SDL_GL_SetSwapInterval(1)

                val host = SdlPlatformHost(window)
                val skia = SkiaRenderer(host, content = content)
                try {
                    renderLoop(window, skia, host)
                } finally {
                    skia.close()
                    host.close()
                }
            } finally {
                SDL_GL_DeleteContext(context)
            }
        } finally {
            SDL_DestroyWindow(window)
        }
    } finally {
        SDL_Quit()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun configureOpenGl() {
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_MAJOR_VERSION, 3)) { "OpenGL major version" }
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_MINOR_VERSION, 3)) { "OpenGL minor version" }
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE.toInt())) {
        "OpenGL core profile"
    }
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_DOUBLEBUFFER, 1)) { "OpenGL double buffering" }
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_RED_SIZE, 8)) { "OpenGL red size" }
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_GREEN_SIZE, 8)) { "OpenGL green size" }
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_BLUE_SIZE, 8)) { "OpenGL blue size" }
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_ALPHA_SIZE, 8)) { "OpenGL alpha size" }
    checkSdl(SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_STENCIL_SIZE, 8)) { "OpenGL stencil size" }
}

@OptIn(ExperimentalForeignApi::class)
private fun renderLoop(
    window: CPointer<SDL_Window>,
    skia: SkiaRenderer,
    host: SdlPlatformHost,
) = memScoped {
    val event = alloc<SDL_Event>()
    val drawableWidth = alloc<IntVar>()
    val drawableHeight = alloc<IntVar>()
    val windowWidth = alloc<IntVar>()
    val windowHeight = alloc<IntVar>()
    var running = true

    while (running) {
        while (SDL_PollEvent(event.ptr) != 0) {
            when (event.type) {
                SDL_QUIT -> running = false
                SDL_WINDOWEVENT -> when (event.window.event.toUInt()) {
                    SDL_WindowEventID.SDL_WINDOWEVENT_FOCUS_GAINED.value ->
                        host.windowInfo.isWindowFocused = true
                    SDL_WindowEventID.SDL_WINDOWEVENT_FOCUS_LOST.value ->
                        host.windowInfo.isWindowFocused = false
                }
                SDL_TEXTINPUT -> {
                    // SDL delivers text only once the keyboard layout, dead keys
                    // and any IME have resolved it, which raw keysyms cannot do.
                    host.textInput.commitText(event.text.text.toKString())
                }
                SDL_KEYDOWN, SDL_KEYUP -> {
                    host.windowInfo.keyboardModifiers = currentPointerModifiers()
                    skia.sendKeyEvent(
                        keyCode = composeKeyCode(event.key.keysym.sym),
                        isDown = event.type == SDL_KEYDOWN,
                        // While a text field is focused SDL_TEXTINPUT is the
                        // authority on what was typed, so a code point is only
                        // synthesised here when nothing is consuming text.
                        codePoint = when {
                            host.textInput.isActive -> 0
                            event.key.keysym.sym in 32..126 -> event.key.keysym.sym
                            else -> 0
                        },
                        ctrl = event.key.keysym.mod.toInt() and KMOD_CTRL.toInt() != 0,
                        meta = event.key.keysym.mod.toInt() and KMOD_GUI.toInt() != 0,
                        alt = event.key.keysym.mod.toInt() and KMOD_ALT.toInt() != 0,
                        shift = event.key.keysym.mod.toInt() and KMOD_SHIFT.toInt() != 0,
                    )
                }
                SDL_MOUSEMOTION, SDL_MOUSEBUTTONDOWN, SDL_MOUSEBUTTONUP, SDL_MOUSEWHEEL -> {
                    SDL_GetWindowSize(window, windowWidth.ptr, windowHeight.ptr)
                    SDL_GL_GetDrawableSize(window, drawableWidth.ptr, drawableHeight.ptr)
                    val scaleX = drawableWidth.value.toFloat() / windowWidth.value.coerceAtLeast(1)
                    val scaleY = drawableHeight.value.toFloat() / windowHeight.value.coerceAtLeast(1)
                    val mouseX = if (event.type == SDL_MOUSEMOTION) event.motion.x else event.button.x
                    val mouseY = if (event.type == SDL_MOUSEMOTION) event.motion.y else event.button.y
                    val state = SDL_GetMouseState(null, null)
                    val changedButton = if (event.type == SDL_MOUSEBUTTONDOWN || event.type == SDL_MOUSEBUTTONUP) {
                        composePointerButton(event.button.button.toInt())
                    } else null
                    skia.sendPointerEvent(
                        type = when (event.type) {
                            SDL_MOUSEBUTTONDOWN -> PointerEventType.Press
                            SDL_MOUSEBUTTONUP -> PointerEventType.Release
                            SDL_MOUSEWHEEL -> PointerEventType.Scroll
                            else -> PointerEventType.Move
                        },
                        x = mouseX * scaleX,
                        y = mouseY * scaleY,
                        scrollX = if (event.type == SDL_MOUSEWHEEL) -event.wheel.preciseX else 0f,
                        scrollY = if (event.type == SDL_MOUSEWHEEL) -event.wheel.preciseY else 0f,
                        buttons = PointerButtons(
                            isPrimaryPressed = state and SDL_BUTTON_LMASK.toUInt() != 0u,
                            isSecondaryPressed = state and SDL_BUTTON_RMASK.toUInt() != 0u,
                            isTertiaryPressed = state and SDL_BUTTON_MMASK.toUInt() != 0u,
                        ),
                        modifiers = currentPointerModifiers(),
                        button = changedButton,
                        timeMillis = event.common.timestamp.toLong(),
                    )
                }
            }
        }

        SDL_GL_GetDrawableSize(window, drawableWidth.ptr, drawableHeight.ptr)
        skia.render(drawableWidth.value, drawableHeight.value)
        SDL_GL_SwapWindow(window)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun currentPointerModifiers(): PointerKeyboardModifiers {
    val modifiers = SDL_GetModState().toInt()
    return PointerKeyboardModifiers(
        isCtrlPressed = modifiers and KMOD_CTRL.toInt() != 0,
        isMetaPressed = modifiers and KMOD_GUI.toInt() != 0,
        isAltPressed = modifiers and KMOD_ALT.toInt() != 0,
        isShiftPressed = modifiers and KMOD_SHIFT.toInt() != 0,
        isCapsLockOn = modifiers and KMOD_CAPS.toInt() != 0,
        isNumLockOn = modifiers and KMOD_NUM.toInt() != 0,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun composePointerButton(button: Int): PointerButton? = when (button) {
    SDL_BUTTON_LEFT.toInt() -> PointerButton.Primary
    SDL_BUTTON_RIGHT.toInt() -> PointerButton.Secondary
    SDL_BUTTON_MIDDLE.toInt() -> PointerButton.Tertiary
    SDL_BUTTON_X1.toInt() -> PointerButton.Back
    SDL_BUTTON_X2.toInt() -> PointerButton.Forward
    else -> null
}

@OptIn(ExperimentalForeignApi::class)
private fun composeKeyCode(symbol: Int): Long = when (symbol) {
    SDLK_LEFT.toInt() -> 37
    SDLK_UP.toInt() -> 38
    SDLK_RIGHT.toInt() -> 39
    SDLK_DOWN.toInt() -> 40
    SDLK_RETURN.toInt() -> 13
    SDLK_ESCAPE.toInt() -> 27
    SDLK_BACKSPACE.toInt() -> 8
    SDLK_TAB.toInt() -> 9
    SDLK_DELETE.toInt() -> 46
    else -> if (symbol in 'a'.code..'z'.code) (symbol - 32).toLong() else symbol.toLong()
}

@OptIn(ExperimentalForeignApi::class)
private inline fun checkSdl(result: Int, operation: () -> String) {
    if (result != 0) error("${operation()} failed: ${SDL_GetError()?.toKString()}")
}
