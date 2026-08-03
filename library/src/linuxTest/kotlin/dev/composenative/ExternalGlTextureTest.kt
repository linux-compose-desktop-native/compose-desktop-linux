package dev.composenative

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves foreign OpenGL renderers composite into the Compose scene as real content.
 *
 * This is the contract libmpv relied on, kept honest without depending on it: the
 * sources below own their framebuffers and textures, clear with their own GL calls,
 * and deliberately leave GL state dirty — exactly what a real external renderer
 * does. If [SkiaRenderer] stops resetting Skia's cached GL state, stops borrowing
 * textures non-owningly, or stops honouring layout bounds, these tests fail.
 */
@OptIn(ExperimentalForeignApi::class)
class ExternalGlTextureTest {
    private var window: CPointer<SDL_Window>? = null
    private var glContext: SDL_GLContext? = null
    private var renderer: SkiaRenderer? = null
    private var host: SdlPlatformHost? = null
    private val sources = mutableListOf<TestGlTextureSource>()

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
    }

    @AfterTest
    fun tearDown() {
        renderer?.close()
        sources.forEach { it.close() }
        sources.clear()
        host?.close()
        glContext?.let(::SDL_GL_DeleteContext)
        window?.let(::SDL_DestroyWindow)
        SDL_Quit()
    }

    private fun source(color: Int): TestGlTextureSource =
        TestGlTextureSource(color).also { sources += it }

    private fun start(content: @Composable () -> Unit): SkiaRenderer =
        SkiaRenderer(checkNotNull(host), content = content).also { renderer = it }

    /** Renders a few frames, because a source has no texture until it has drawn once. */
    private fun SkiaRenderer.renderFrames(count: Int = 3) = repeat(count) { render(WIDTH, HEIGHT) }

    private fun SkiaRenderer.probe(x: Int, y: Int): Int {
        val bitmap = snapshot()
        try {
            return bitmap.getColor(x, y) and 0xFFFFFF
        } finally {
            bitmap.close()
        }
    }

    @Test
    fun severalTexturesCompositeIndependently() {
        val left = source(GREEN)
        val right = source(ORANGE)
        val renderer = start {
            SideBySide(
                start = { ExternalTexture(left) },
                end = { ExternalTexture(right) },
            )
        }
        renderer.renderFrames()

        assertEquals(GREEN, renderer.probe(WIDTH / 4, HEIGHT / 2), "left half should be the first texture")
        assertEquals(ORANGE, renderer.probe(WIDTH * 3 / 4, HEIGHT / 2), "right half should be the second texture")
    }

    @Test
    fun textureIsConfinedToItsLayoutBounds() {
        val texture = source(GREEN)
        val renderer = start {
            SideBySide(
                start = { ExternalTexture(texture) },
                end = { /* deliberately empty: background must show through */ },
            )
        }
        renderer.renderFrames()

        assertEquals(GREEN, renderer.probe(WIDTH / 4, HEIGHT / 2), "texture should fill its own node")
        assertEquals(
            BACKGROUND,
            renderer.probe(WIDTH * 3 / 4, HEIGHT / 2),
            "texture must not paint outside its node",
        )
    }

    @Test
    fun composeContentDrawsOverTheTexture() {
        val texture = source(GREEN)
        val renderer = start {
            Layout(
                content = {
                    ExternalTexture(texture)
                    Overlay()
                },
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEach { it.place(0, 0) }
                }
            }
        }
        renderer.renderFrames()

        assertEquals(OVERLAY, renderer.probe(100, 90), "Compose content should cover the texture")
        assertEquals(GREEN, renderer.probe(WIDTH - 40, HEIGHT - 40), "texture visible where Compose does not draw")
    }

    @Test
    fun composeSurvivesForeignGlStateChanges() {
        val texture = source(GREEN)
        val renderer = start {
            Layout(
                content = {
                    ExternalTexture(texture)
                    Overlay()
                },
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEach { it.place(0, 0) }
                }
            }
        }
        // Several frames: Skia's cached GL state is only wrong on the frames that
        // follow foreign GL work.
        renderer.renderFrames(count = 4)

        assertEquals(
            OVERLAY,
            renderer.probe(100, 90),
            "Compose must survive an external renderer dirtying GL state",
        )
    }

    @Test
    fun skiaNeverDeletesABorrowedTexture() {
        val texture = source(GREEN)
        val renderer = start { ExternalTexture(texture) }
        renderer.renderFrames()

        assertTrue(
            glIsTexture(texture.textureId.toUInt()) == GL_TRUE.toUByte(),
            "Skia must not delete a texture owned by the external renderer",
        )
    }

    @Test
    fun leavingCompositionReleasesTheWrapperButNotTheTexture() {
        val texture = source(GREEN)
        val visible = mutableStateOf(true)
        val renderer = start {
            if (visible.value) ExternalTexture(texture)
        }
        renderer.renderFrames()
        assertTrue(renderer.externalTextures.isRegistered(texture), "should be registered while composed")

        visible.value = false
        renderer.renderFrames()

        assertFalse(renderer.externalTextures.isRegistered(texture), "should unregister when removed")
        assertTrue(
            glIsTexture(texture.textureId.toUInt()) == GL_TRUE.toUByte(),
            "releasing the wrapper must not delete the caller's texture",
        )
        assertEquals(BACKGROUND, renderer.probe(WIDTH / 2, HEIGHT / 2), "texture should no longer be drawn")
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240

        const val GREEN = 0x20C060
        const val ORANGE = 0xE08020
        const val OVERLAY = 0x5F87FF

        /** Matches SkiaRenderer's default clear colour. */
        const val BACKGROUND = 0x0E1117
    }
}

/** Splits the window in half so two nodes can be probed independently. */
@Composable
private fun SideBySide(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    Layout(contents = listOf(start, end)) { (startMeasurables, endMeasurables), constraints ->
        val half = constraints.maxWidth / 2
        val halfConstraints = constraints.copy(minWidth = half, maxWidth = half)
        val startPlaceables = startMeasurables.map { it.measure(halfConstraints) }
        val endPlaceables = endMeasurables.map { it.measure(halfConstraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            startPlaceables.forEach { it.place(0, 0) }
            endPlaceables.forEach { it.place(half, 0) }
        }
    }
}

/** Opaque content in a known region, for probing what covers what. */
@Composable
private fun Overlay() {
    Layout(
        modifier = Modifier.drawBehind {
            drawRect(
                color = ComposeColor(0xFF5F87FF),
                topLeft = Offset(48f, 48f),
                size = Size(160f, 96f),
            )
        },
    ) { _, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {}
    }
}

/**
 * A minimal stand-in for a real external renderer such as libmpv.
 *
 * It owns an FBO and texture, draws with plain GL, and intentionally leaves
 * scissor, blend, the active texture unit and its own framebuffer binding in
 * place. That combination is what actually reproduces the corruption: removing
 * the renderer's `resetGLAll()` makes
 * [ExternalGlTextureTest.composeSurvivesForeignGlStateChanges] fail.
 */
@OptIn(ExperimentalForeignApi::class)
private class TestGlTextureSource(private val color: Int) : ExternalGlTexture, AutoCloseable {
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
        glClearColor(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            1f,
        )
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
        if (width <= 0 || height <= 0 ||
            (this@TestGlTextureSource.width == width && this@TestGlTextureSource.height == height)
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
