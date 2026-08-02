package dev.composenative

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.Layout
import cnames.structs.SDL_Window
import dev.composenative.interop.*
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.setenv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves a foreign OpenGL renderer can composite into the Compose scene.
 *
 * This is the contract libmpv relied on, kept honest without depending on it:
 * the source below owns its own framebuffer and texture, clears with its own GL
 * calls, and deliberately leaves GL state dirty — exactly what a real external
 * renderer does. If [SkiaRenderer] stops resetting Skia's cached GL state, or
 * stops borrowing the texture non-owningly, these tests fail.
 */
@OptIn(ExperimentalForeignApi::class)
class ExternalGlTextureTest {
    private var window: CPointer<SDL_Window>? = null
    private var glContext: SDL_GLContext? = null
    private var renderer: SkiaRenderer? = null
    private var host: SdlPlatformHost? = null
    private var source: TestGlTextureSource? = null

    @BeforeTest
    fun setUp() {
        setenv("SDL_VIDEODRIVER", "offscreen", 1)
        check(SDL_Init(SDL_INIT_VIDEO or SDL_INIT_EVENTS) == 0) { "SDL_Init failed" }
        SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_MAJOR_VERSION, 3)
        SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_MINOR_VERSION, 3)
        SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE.toInt())
        SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_DOUBLEBUFFER, 1)
        SDL_GL_SetAttribute(SDL_GLattr.SDL_GL_STENCIL_SIZE, 8)

        window = SDL_CreateWindow(
            "external-texture-test", 0, 0, WIDTH, HEIGHT, SDL_WINDOW_OPENGL or SDL_WINDOW_HIDDEN,
        )
        val createdWindow = checkNotNull(window) { "SDL_CreateWindow failed" }
        glContext = checkNotNull(SDL_GL_CreateContext(createdWindow)) { "SDL_GL_CreateContext failed" }
        check(SDL_GL_MakeCurrent(createdWindow, glContext) == 0) { "SDL_GL_MakeCurrent failed" }

        host = SdlPlatformHost(createdWindow)
        renderer = SkiaRenderer(checkNotNull(host)) { OverlayContent() }
        source = TestGlTextureSource()
    }

    @AfterTest
    fun tearDown() {
        source?.close()
        renderer?.close()
        host?.close()
        glContext?.let(::SDL_GL_DeleteContext)
        window?.let(::SDL_DestroyWindow)
        SDL_Quit()
    }

    @Test
    fun externalTextureIsCompositedUnderTheComposeScene() {
        val renderer = checkNotNull(renderer)
        renderer.render(WIDTH, HEIGHT, checkNotNull(source))

        val bitmap = renderer.snapshot()
        try {
            // Away from the Compose overlay, the external texture must show
            // through rather than the surface clear colour.
            assertEquals(
                SOURCE_COLOR,
                bitmap.getColor(WIDTH - 40, HEIGHT - 40) and 0xFFFFFF,
                "external texture should be visible where Compose draws nothing",
            )

            // Where Compose does draw, its content must win: the scene is
            // composited over the texture, not blended into oblivion by it.
            assertEquals(
                OVERLAY_COLOR,
                bitmap.getColor(100, 90) and 0xFFFFFF,
                "Compose content should composite opaquely over the texture",
            )
        } finally {
            bitmap.close()
        }
    }

    @Test
    fun composeStillRendersAfterForeignGlStateChanges() {
        val renderer = checkNotNull(renderer)
        val source = checkNotNull(source)

        // Several frames, because Skia's cached GL state is only wrong on the
        // frames that follow foreign GL work.
        repeat(3) { renderer.render(WIDTH, HEIGHT, source) }

        val bitmap = renderer.snapshot()
        try {
            assertEquals(
                OVERLAY_COLOR,
                bitmap.getColor(100, 90) and 0xFFFFFF,
                "Compose must survive an external renderer dirtying GL state",
            )
        } finally {
            bitmap.close()
        }
    }

    @Test
    fun textureIsNotDeletedBySkia() {
        val renderer = checkNotNull(renderer)
        val source = checkNotNull(source)
        renderer.render(WIDTH, HEIGHT, source)

        // Skia borrows rather than adopts, so the texture must still be a live
        // GL object after the frame that referenced it is gone.
        renderer.render(WIDTH, HEIGHT, source)
        assertTrue(
            glIsTexture(source.textureId.toUInt()) == GL_TRUE.toUByte(),
            "Skia must not delete a texture owned by the external renderer",
        )
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240

        /** Matches the clear colour in [TestGlTextureSource]. */
        const val SOURCE_COLOR = 0x20C060

        /** Matches the rect drawn by [OverlayContent]. */
        const val OVERLAY_COLOR = 0x5F87FF
    }
}

/**
 * A minimal stand-in for a real external renderer such as libmpv.
 *
 * It owns an FBO and texture, draws with plain GL, and intentionally leaves
 * scissor, blend, the active texture unit and its own framebuffer binding in
 * place. That combination is what actually reproduces the corruption: removing
 * the renderer's `resetGLAll()` makes
 * [ExternalGlTextureTest.composeStillRendersAfterForeignGlStateChanges] fail.
 */
@OptIn(ExperimentalForeignApi::class)
private class TestGlTextureSource : ExternalGlTexture, AutoCloseable {
    private var framebuffer = 0u
    private var texture = 0u

    override var width = 0
        private set

    override var height = 0
        private set

    override val textureId: Int get() = texture.toInt()

    override fun render(width: Int, height: Int) {
        ensureTarget(width, height)
        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), framebuffer)
        glViewport(0, 0, width, height)
        glClearColor(0x20 / 255f, 0xC0 / 255f, 0x60 / 255f, 1f)
        glClear(GL_COLOR_BUFFER_BIT.toUInt())

        // Leave state dirty on purpose; a real external renderer does the same
        // and is under no obligation to restore what Skia happens to cache.
        glEnable(GL_SCISSOR_TEST.toUInt())
        glScissor(0, 0, width / 2, height / 2)
        glBindTexture(GL_TEXTURE_2D.toUInt(), texture)
        glEnable(GL_BLEND.toUInt())
        glBlendFunc(GL_ONE.toUInt(), GL_ONE.toUInt())
        glDisable(GL_DEPTH_TEST.toUInt())
        glActiveTexture(GL_TEXTURE1.toUInt())
        // Deliberately leaves its own framebuffer bound.
    }

    private fun ensureTarget(width: Int, height: Int) = memScoped {
        if (width <= 0 || height <= 0 || (this@TestGlTextureSource.width == width &&
                this@TestGlTextureSource.height == height)
        ) {
            return@memScoped
        }
        if (framebuffer == 0u) {
            val id = alloc<UIntVar>()
            glGenFramebuffers(1, id.ptr)
            framebuffer = id.value
            glGenTextures(1, id.ptr)
            texture = id.value
        }
        this@TestGlTextureSource.width = width
        this@TestGlTextureSource.height = height

        glBindTexture(GL_TEXTURE_2D.toUInt(), texture)
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_MIN_FILTER.toUInt(), GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_MAG_FILTER.toUInt(), GL_LINEAR)
        glTexImage2D(
            GL_TEXTURE_2D.toUInt(), 0, GL_RGBA8, width, height, 0,
            GL_RGBA.toUInt(), GL_UNSIGNED_BYTE.toUInt(), null,
        )
        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), framebuffer)
        glFramebufferTexture2D(
            GL_FRAMEBUFFER.toUInt(), GL_COLOR_ATTACHMENT0.toUInt(),
            GL_TEXTURE_2D.toUInt(), texture, 0,
        )
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER.toUInt()) == GL_FRAMEBUFFER_COMPLETE.toUInt()) {
            "Could not create the test off-screen framebuffer"
        }
        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), 0u)
        glBindTexture(GL_TEXTURE_2D.toUInt(), 0u)
    }

    override fun close() = memScoped {
        val id = alloc<UIntVar>()
        if (framebuffer != 0u) {
            id.value = framebuffer
            glDeleteFramebuffers(1, id.ptr)
        }
        if (texture != 0u) {
            id.value = texture
            glDeleteTextures(1, id.ptr)
        }
    }
}

/**
 * Compose content covering a known region, so a captured frame can distinguish
 * "Compose drew here" from "the external texture shows through here".
 *
 * Deliberately transparent everywhere else: an opaque background would hide the
 * texture composited underneath and make the test vacuous.
 */
@Composable
private fun OverlayContent() {
    Layout(
        content = {},
        modifier = Modifier.drawBehind {
            drawRect(
                color = ComposeColor(0xFF5F87FF),
                topLeft = Offset(48f, 48f),
                size = Size(320f, 96f),
            )
        },
    ) { _, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {}
    }
}
