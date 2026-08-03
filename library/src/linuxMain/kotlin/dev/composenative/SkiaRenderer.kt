package dev.composenative

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.clearSkikoComposeImplementation
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.installPostDelayedDispatcher
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.composenative.interop.SDL_GL_GetProcAddress
import dev.composenative.interop.GL_RGBA8
import dev.composenative.interop.GL_TEXTURE_2D
import kotlinx.coroutines.Dispatchers
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeNullPtr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fprintf
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.stderr
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.makeGLWithInterface
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
private fun resolveOpenGlFunction(
    context: COpaquePointer?,
    name: CPointer<ByteVar>?,
): COpaquePointer? {
    @Suppress("UNUSED_VARIABLE")
    val ignored = context
    return SDL_GL_GetProcAddress(name?.toKString())
}

/**
 * Owns Skia's view of the SDL OpenGL back buffer.
 *
 * SDL must have made its OpenGL context current before this object is created
 * or used. The wrapped framebuffer remains owned by SDL/OpenGL.
 */
@OptIn(ExperimentalForeignApi::class, InternalComposeUiApi::class)
class SkiaRenderer(
    private val host: SdlPlatformHost,
    private val backgroundColor: Int = Color.makeARGB(255, 14, 17, 23),
    content: @Composable () -> Unit,
) : AutoCloseable {
    init {
        installPostDelayedDispatcher(Dispatchers.Unconfined)
        registerSkikoComposeImplementation()
    }

    private val glResolver = staticCFunction(::resolveOpenGlFunction)
    private val glInterface = GLAssembledInterface.createFromNativePointers(
        ctxPtr = nativeNullPtr,
        fPtr = glResolver.rawValue,
    )
    private val context = DirectContext.makeGLWithInterface(glInterface)
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var framesRendered = 0
    private var width = 0
    private var height = 0
    private val clockOrigin = TimeSource.Monotonic.markNow()
    private val frameRecomposer = FrameRecomposer(Dispatchers.Unconfined)
    private var density = Density(host.currentDensity())
    private val scene: ComposeScene = CanvasLayersComposeScene(
        frameRecomposer = frameRecomposer,
        density = density,
        platformContext = host.platformContext,
    )

    /** Registry of the external textures currently in the scene. */
    internal val externalTextures = ExternalTextureHost(context)

    init {
        scene.setContent {
            CompositionLocalProvider(LocalExternalTextureHost provides externalTextures) {
                content()
            }
        }
    }

    fun render(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        ensureSurface(width, height)

        // Density can change while running, when the window moves to a display
        // with a different scale factor, so it is refreshed per frame rather
        // than captured once at startup.
        val currentDensity = host.currentDensity()
        if (currentDensity != density.density) {
            density = Density(currentDensity)
            scene.density = density
        }

        val size = IntSize(width, height)
        scene.size = size
        host.windowInfo.containerSize = size

        // Compose first, and only then the foreign renderers. Composition is what
        // registers the external textures, and layout is what tells them how big
        // they are, so running both first means each one draws at the size it was
        // actually laid out to rather than the size it had last frame. Neither
        // composition nor layout issues GL commands.
        frameRecomposer.performFrame(clockOrigin.elapsedNow().inWholeNanoseconds)
        scene.measureAndLayout()

        if (externalTextures.renderAll()) {
            // Those renderers have just drawn with their own programs, buffers and
            // bindings. Skia caches what it believes the GL context looks like, so
            // every one of those assumptions is now stale and must be dropped, or
            // Skia will emit draws against their state. Once is enough, however
            // many of them ran.
            context.resetGLAll()
        }

        val currentSurface = checkNotNull(surface)
        val canvas = currentSurface.canvas
        canvas.clear(backgroundColor)
        scene.draw(canvas.asComposeCanvas())

        currentSurface.flushAndSubmit()
        captureIfRequested(currentSurface)
    }

    /**
     * Writes one composited frame to the path in `COMPOSE_NATIVE_CAPTURE`.
     *
     * This is the only way to confirm what actually reached the framebuffer when
     * running under SDL's offscreen driver, where there is no window to look at.
     * The frame is taken after [Surface.flushAndSubmit] so it includes any
     * external texture and the Compose scene exactly as they were composited.
     */
    /**
     * Reads the last composited frame back into a raster bitmap.
     *
     * The surface snapshot is GPU-backed and Skia will not encode a
     * texture-backed image directly, so a readback is required either way. The
     * caller owns the returned bitmap.
     */
    internal fun snapshot(): Bitmap {
        val currentSurface = checkNotNull(surface) { "Nothing has been rendered yet" }
        val bitmap = Bitmap()
        check(bitmap.allocN32Pixels(currentSurface.width, currentSurface.height, opaque = true)) {
            "Could not allocate a raster bitmap for the captured frame"
        }
        check(currentSurface.readPixels(bitmap, 0, 0)) {
            "Could not read the captured frame back from the GPU"
        }
        return bitmap
    }

    private fun captureIfRequested(surface: Surface) {
        val path = getenv("COMPOSE_NATIVE_CAPTURE")?.toKString() ?: return
        // Skip the first frames: an external renderer may need several before it
        // has produced anything.
        if (framesRendered++ != CAPTURE_FRAME) return

        val bitmap = snapshot()
        try {
            val image = Image.makeFromBitmap(bitmap)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)
                    ?: error("Skia could not encode the captured frame")
                try {
                    writeFile(path, data.bytes)
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            bitmap.close()
        }
        fprintf(stderr, "captured frame %d to %s\n", CAPTURE_FRAME, path)
    }

    fun sendPointerEvent(
        type: PointerEventType,
        x: Float,
        y: Float,
        scrollX: Float = 0f,
        scrollY: Float = 0f,
        buttons: PointerButtons = PointerButtons(),
        modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        button: PointerButton? = null,
        timeMillis: Long,
    ) {
        scene.sendPointerEvent(
            eventType = type,
            position = Offset(x, y),
            scrollDelta = Offset(scrollX, scrollY),
            buttons = buttons,
            keyboardModifiers = modifiers,
            button = button,
            timeMillis = timeMillis,
        )
    }

    fun sendKeyEvent(
        keyCode: Long,
        isDown: Boolean,
        codePoint: Int,
        ctrl: Boolean,
        meta: Boolean,
        alt: Boolean,
        shift: Boolean,
    ): Boolean = scene.sendKeyEvent(
        KeyEvent(
            key = Key(keyCode),
            type = if (isDown) KeyEventType.KeyDown else KeyEventType.KeyUp,
            codePoint = codePoint,
            isCtrlPressed = ctrl,
            isMetaPressed = meta,
            isAltPressed = alt,
            isShiftPressed = shift,
        )
    )

    private fun ensureSurface(newWidth: Int, newHeight: Int) {
        if (surface != null && width == newWidth && height == newHeight) return
        surface?.close()
        renderTarget?.close()

        width = newWidth
        height = newHeight
        renderTarget = BackendRenderTarget.makeGL(
            width = width,
            height = height,
            sampleCnt = 0,
            stencilBits = 8,
            fbId = 0,
            fbFormat = FramebufferFormat.GR_GL_RGBA8,
        )
        surface = Surface.makeFromBackendRenderTarget(
            context = context,
            rt = checkNotNull(renderTarget),
            origin = SurfaceOrigin.BOTTOM_LEFT,
            colorFormat = SurfaceColorFormat.RGBA_8888,
            colorSpace = ColorSpace.sRGB,
            surfaceProps = SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN),
        ) ?: error("Skia could not wrap SDL's OpenGL framebuffer")
    }

    override fun close() {
        scene.close()
        frameRecomposer.close()
        externalTextures.close()
        surface?.close()
        renderTarget?.close()
        context.close()
        glInterface.close()
        clearSkikoComposeImplementation()
    }
}

/** Frame index captured by [SkiaRenderer.captureIfRequested]. */
private const val CAPTURE_FRAME = 30

@OptIn(ExperimentalForeignApi::class)
private fun writeFile(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("Could not open $path for writing")
    try {
        bytes.usePinned { pinned ->
            val written = fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
            check(written == bytes.size.toULong()) { "Short write to $path" }
        }
    } finally {
        fclose(file)
    }
}
