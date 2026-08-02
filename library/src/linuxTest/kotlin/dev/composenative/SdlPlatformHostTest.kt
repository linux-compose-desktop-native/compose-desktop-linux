package dev.composenative

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.LinuxPointerIcon
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import cnames.structs.SDL_Window
import dev.composenative.interop.*
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.setenv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the SDL platform surface without needing a display.
 *
 * SDL's offscreen driver still implements cursors, clipboard and text-input
 * state, so the whole host can be driven headlessly. No OpenGL context is
 * created here: none of this depends on rendering.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SdlPlatformHostTest {
    private var window: CPointer<SDL_Window>? = null
    private var host: SdlPlatformHost? = null

    @BeforeTest
    fun setUp() {
        setenv("SDL_VIDEODRIVER", "offscreen", 1)
        check(SDL_Init(SDL_INIT_VIDEO or SDL_INIT_EVENTS) == 0) {
            "SDL_Init failed: ${SDL_GetError()?.let { it }}"
        }
        window = SDL_CreateWindow("test", 0, 0, 320, 200, SDL_WINDOW_HIDDEN)
        host = SdlPlatformHost(checkNotNull(window) { "SDL_CreateWindow failed" })
    }

    @AfterTest
    fun tearDown() {
        host?.close()
        window?.let(::SDL_DestroyWindow)
        SDL_Quit()
    }

    /**
     * Verifies the Compose-to-SDL cursor mapping. SDL's offscreen driver has no
     * cursor support, so `SDL_CreateSystemCursor` returns null here and the
     * `SDL_SetCursor` call itself is only exercised on a real display; what is
     * checked is that each Compose icon selects the right SDL cursor.
     */
    @Test
    fun composeCursorRequestsReachSdl() {
        val context = checkNotNull(host).platformContext
        val cursors = checkNotNull(host).cursors

        context.setPointerIcon(LinuxPointerIcon.Hand)
        assertEquals(SDL_SystemCursor.SDL_SYSTEM_CURSOR_HAND, cursors.active)

        context.setPointerIcon(LinuxPointerIcon.Text)
        assertEquals(SDL_SystemCursor.SDL_SYSTEM_CURSOR_IBEAM, cursors.active)

        context.setPointerIcon(LinuxPointerIcon.Crosshair)
        assertEquals(SDL_SystemCursor.SDL_SYSTEM_CURSOR_CROSSHAIR, cursors.active)

        context.setPointerIcon(LinuxPointerIcon.Default)
        assertEquals(SDL_SystemCursor.SDL_SYSTEM_CURSOR_ARROW, cursors.active)
    }

    @Test
    fun clipboardRoundTripsThroughSdl() {
        val clipboard = SdlClipboard()

        clipboard.setText("borrowed texture")
        assertEquals("borrowed texture", clipboard.getText())

        // An empty clipboard must read as absent rather than as an empty string,
        // so that paste is a no-op instead of clearing the target.
        clipboard.setText(null)
        assertNull(clipboard.getText())
    }

    @Test
    fun textInputTracksFocusAndForwardsCommittedText() {
        val textInput = checkNotNull(host).textInput
        assertFalse(textInput.isActive, "text input must stay off until a field asks for it")

        val commands = mutableListOf<EditCommand>()
        textInput.startInput(
            value = androidx.compose.ui.text.input.TextFieldValue(),
            imeOptions = androidx.compose.ui.text.input.ImeOptions.Default,
            onEditCommand = { commands += it },
            onImeActionPerformed = {},
        )
        assertTrue(textInput.isActive)

        // What SDL_TEXTINPUT delivers: already-resolved text, possibly several
        // code points at once, which raw keysyms could not produce.
        textInput.commitText("né")
        assertContentEquals(listOf(CommitTextCommand("né", 1)), commands)

        commands.clear()
        textInput.commitText("")
        assertTrue(commands.isEmpty(), "empty commits must not reach Compose")

        textInput.stopInput()
        assertFalse(textInput.isActive)

        textInput.commitText("dropped")
        assertTrue(commands.isEmpty(), "text must not be delivered after input stops")
    }

    @Test
    fun densityIsDerivedFromTheDrawableToWindowRatio() {
        val density = checkNotNull(host).currentDensity()
        assertTrue(density > 0f, "density must be positive, was $density")
    }

    @Test
    fun inputModeManagerReportsKeyboard() {
        val inputModeManager = checkNotNull(host).platformContext.inputModeManager
        assertEquals(InputMode.Keyboard, inputModeManager.inputMode)
        assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard))
        assertFalse(inputModeManager.requestInputMode(InputMode.Touch))
    }
}
