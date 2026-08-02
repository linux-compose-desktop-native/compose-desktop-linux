package dev.composenative

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.pointer.LinuxPointerIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.PlatformClipboardText
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.installPlatformClipboardText
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import cnames.structs.SDL_Cursor
import cnames.structs.SDL_Window
import dev.composenative.interop.*
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

/**
 * The SDL-backed platform surface Compose renders against.
 *
 * This is the boundary described in DESIGN.md: SDL types stay on this side of
 * it, and Compose only ever sees its own platform interfaces. Everything here
 * runs on the single UI/render thread that owns the OpenGL context.
 */
@OptIn(ExperimentalForeignApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class SdlPlatformHost(private val window: CPointer<SDL_Window>) : AutoCloseable {

    internal val cursors = SdlCursors()
    private val clipboard = SdlClipboard()

    val windowInfo = SdlWindowInfo()
    val textInput = SdlTextInputService(window)

    val platformContext: PlatformContext = object : PlatformContext {
        override val windowInfo: WindowInfo get() = this@SdlPlatformHost.windowInfo

        override val inputModeManager: InputModeManager = SdlInputModeManager()

        override val textInputService: PlatformTextInputService get() = textInput

        override fun setPointerIcon(pointerIcon: PointerIcon) = cursors.apply(pointerIcon)
    }

    init {
        installPlatformClipboardText(clipboard)
    }

    /** Density is the ratio between the drawable and the window in screen coordinates. */
    fun currentDensity(): Float {
        val drawable = drawableSize(window)
        val logical = windowSize(window)
        if (logical.width <= 0 || logical.height <= 0) return 1f
        return drawable.width.toFloat() / logical.width
    }

    override fun close() {
        installPlatformClipboardText(null)
        cursors.close()
    }
}

/** Window state Compose polls; the SDL event loop keeps it current. */
class SdlWindowInfo : WindowInfo {
    override var isWindowFocused: Boolean = true
    override var containerSize: IntSize = IntSize.Zero
    override var keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()
}

private class SdlInputModeManager : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    // A desktop host is always capable of keyboard input, and has no touch mode
    // to switch into, so only a keyboard request can be honoured.
    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}

/**
 * Maps Compose's cursor requests onto SDL system cursors.
 *
 * SDL owns every cursor it creates, so they are created once, reused, and freed
 * together with the host.
 */
@OptIn(ExperimentalForeignApi::class)
internal class SdlCursors : AutoCloseable {
    // Null values are cached deliberately: a video driver that cannot provide
    // cursors at all (the offscreen one, for instance) would otherwise be asked
    // to build the same cursor again on every pointer move.
    private val cache = mutableMapOf<SDL_SystemCursor, CPointer<SDL_Cursor>?>()

    /** The cursor Compose last asked for, whether or not SDL could provide it. */
    var active: SDL_SystemCursor? = null
        private set

    fun apply(pointerIcon: PointerIcon) {
        val requested = when (pointerIcon) {
            LinuxPointerIcon.Crosshair -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_CROSSHAIR
            LinuxPointerIcon.Text -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_IBEAM
            LinuxPointerIcon.Hand -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_HAND
            else -> SDL_SystemCursor.SDL_SYSTEM_CURSOR_ARROW
        }
        if (requested == active) return
        active = requested

        cache.getOrPut(requested) { SDL_CreateSystemCursor(requested) }?.let(::SDL_SetCursor)
    }

    override fun close() {
        cache.values.filterNotNull().forEach(::SDL_FreeCursor)
        cache.clear()
        active = null
    }
}

/** Bridges Compose's clipboard to SDL's, which is the real system clipboard. */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
internal class SdlClipboard : PlatformClipboardText {
    override fun getText(): String? {
        if (SDL_HasClipboardText() != SDL_TRUE) return null
        // SDL hands back a copy that the caller owns and must release.
        val owned = SDL_GetClipboardText() ?: return null
        return try {
            owned.toKString().takeIf { it.isNotEmpty() }
        } finally {
            SDL_free(owned)
        }
    }

    override fun setText(text: String?) {
        SDL_SetClipboardText(text ?: "")
    }
}

/**
 * Drives SDL's text-input state on behalf of Compose.
 *
 * SDL only emits `SDL_TEXTINPUT` while text input is active, so a focused text
 * field must turn it on. Committed text arrives through the event loop rather
 * than here, because only SDL knows when the compositor or IME has resolved it.
 */
@OptIn(ExperimentalForeignApi::class)
class SdlTextInputService(private val window: CPointer<SDL_Window>) : PlatformTextInputService {
    private var onEditCommand: ((List<EditCommand>) -> Unit)? = null

    /** Whether a text field currently wants keystrokes. */
    var isActive: Boolean = false
        private set

    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit,
    ) {
        this.onEditCommand = onEditCommand
        startInput()
    }

    override fun startInput() {
        isActive = true
        SDL_StartTextInput()
    }

    override fun stopInput() {
        isActive = false
        onEditCommand = null
        SDL_StopTextInput()
    }

    override fun showSoftwareKeyboard() = Unit

    override fun hideSoftwareKeyboard() = Unit

    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) = Unit

    /** Forwards text SDL has committed, which may be several code points at once. */
    fun commitText(text: String) {
        if (text.isEmpty()) return
        onEditCommand?.invoke(listOf(CommitTextCommand(text, newCursorPosition = 1)))
    }

    /** Tells SDL where an IME candidate window should appear, in window coordinates. */
    fun setCursorRect(x: Int, y: Int, width: Int, height: Int) = memScoped {
        val rect = alloc<SDL_Rect> {
            this.x = x
            this.y = y
            this.w = width
            this.h = height
        }
        SDL_SetTextInputRect(rect.ptr)
    }
}

/** Size of the OpenGL back buffer, in pixels. */
@OptIn(ExperimentalForeignApi::class)
internal fun drawableSize(window: CPointer<SDL_Window>): IntSize = memScoped {
    val width = alloc<IntVar>()
    val height = alloc<IntVar>()
    SDL_GL_GetDrawableSize(window, width.ptr, height.ptr)
    IntSize(width.value, height.value)
}

/** Size of the window in screen coordinates, which is smaller than the drawable on HiDPI. */
@OptIn(ExperimentalForeignApi::class)
internal fun windowSize(window: CPointer<SDL_Window>): IntSize = memScoped {
    val width = alloc<IntVar>()
    val height = alloc<IntVar>()
    SDL_GetWindowSize(window, width.ptr, height.ptr)
    IntSize(width.value, height.value)
}
