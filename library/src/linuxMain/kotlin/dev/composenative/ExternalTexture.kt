package dev.composenative

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SurfaceOrigin
import dev.composenative.interop.*
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Displays an [ExternalGlTexture] as ordinary Compose content.
 *
 * The texture participates in layout like any other node: it is sized by its
 * constraints, clipped and transformed by its modifiers, and composited in tree
 * order, so Compose content can sit above it, below it, or around it. Any number
 * of these can exist in one scene.
 *
 * By default it fills the constraints it is given. That default matters more than
 * usual here, because without compose-foundation there is no `Modifier.fillMaxSize`
 * to reach for.
 *
 * [source] is rendered once per frame at the size this node was laid out to, before
 * the scene is drawn. If the same source is shown by several composables it is
 * rendered once, at the largest requested size, and each one scales it into its own
 * bounds.
 */
@Composable
fun ExternalTexture(
    source: ExternalGlTexture,
    modifier: Modifier = Modifier,
) {
    val host = LocalExternalTextureHost.current

    DisposableEffect(host, source) {
        host.register(source)
        onDispose { host.unregister(source) }
    }

    Layout(
        modifier = modifier
            .onSizeChanged { host.setSize(source, it) }
            .drawBehind {
                val image = host.imageFor(source) ?: return@drawBehind
                // Every DrawScope.drawImage overload takes an ImageBitmap, and
                // converting a texture-backed Image to one forces a GPU->CPU
                // readback every frame. Dropping to the Skia canvas keeps the
                // texture on the GPU.
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawImageRect(
                        image,
                        Rect.makeWH(size.width, size.height),
                    )
                }
            },
    ) { _, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {}
    }
}

internal val LocalExternalTextureHost = staticCompositionLocalOf<ExternalTextureHost> {
    error("ExternalTexture can only be used inside composeDesktopApplication")
}

/**
 * Tracks the external textures currently in the scene.
 *
 * Foreign OpenGL work cannot happen while Skia is drawing, so it cannot happen
 * inside a composable's draw block. Composables register their source here
 * instead; the renderer draws every registered source at the start of the frame,
 * resets Skia's cached GL state once, and only then lets the scene draw.
 *
 * Everything here runs on the single UI/render thread that owns the GL context.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ExternalTextureHost(private val context: DirectContext) : AutoCloseable {

    private class Entry {
        var requestedSize: IntSize = IntSize.Zero
        var backendTexture: BackendTexture? = null
        var image: Image? = null

        // Cache identity of the wrapper currently held. origin and alphaType are
        // included deliberately: a source that flips either without changing its
        // id or size would otherwise keep a stale wrapper.
        var textureId: Int = 0
        var width: Int = 0
        var height: Int = 0
        var origin: SurfaceOrigin? = null
        var alphaType: ColorAlphaType? = null

        fun release() {
            image?.close()
            backendTexture?.close()
            image = null
            backendTexture = null
            textureId = 0
        }
    }

    private val entries = LinkedHashMap<ExternalGlTexture, Entry>()

    fun register(source: ExternalGlTexture) {
        entries.getOrPut(source) { Entry() }
    }

    fun unregister(source: ExternalGlTexture) {
        entries.remove(source)?.release()
    }

    /** Records the size this source's node was laid out to. */
    fun setSize(source: ExternalGlTexture, size: IntSize) {
        val entry = entries[source] ?: return
        // A source shown by several nodes renders once, at the largest size asked
        // for, and each node scales it down into its own bounds.
        if (size.width > entry.requestedSize.width || size.height > entry.requestedSize.height) {
            entry.requestedSize = IntSize(
                maxOf(size.width, entry.requestedSize.width),
                maxOf(size.height, entry.requestedSize.height),
            )
        }
    }

    /**
     * Lets every registered source draw its next frame.
     *
     * @return true if any source rendered, meaning Skia's cached GL state is now stale.
     */
    fun renderAll(): Boolean {
        var rendered = false
        for ((source, entry) in entries) {
            val size = entry.requestedSize
            if (size.width <= 0 || size.height <= 0) continue
            resetToBaseline()
            source.render(size.width, size.height)
            rendered = true
        }
        return rendered
    }

    /**
     * Restores the GL state each source is promised before it draws.
     *
     * Sources are told they need not restore anything, which is only coherent if
     * one cannot break the next. Left alone they can: a source that leaves the
     * scissor test enabled silently clips the *following* source's `glClear` to
     * its own box, which shows up as a partially drawn texture rather than an
     * error. This is a fixed, documented baseline, not a general sanitiser —
     * anything beyond it is the source's own responsibility.
     */
    private fun resetToBaseline() {
        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), 0u)
        glDisable(GL_SCISSOR_TEST.toUInt())
        glDisable(GL_BLEND.toUInt())
        glDisable(GL_DEPTH_TEST.toUInt())
        glDisable(GL_STENCIL_TEST.toUInt())
        glActiveTexture(GL_TEXTURE0.toUInt())
        glBindTexture(GL_TEXTURE_2D.toUInt(), 0u)
        glColorMask(1u, 1u, 1u, 1u)
    }

    /** The borrowed image for [source], or null when it has no frame yet. */
    fun imageFor(source: ExternalGlTexture): Image? {
        val entry = entries[source] ?: return null
        val textureId = source.textureId
        if (textureId == 0 || source.width <= 0 || source.height <= 0) return null

        val unchanged = entry.image != null &&
            entry.textureId == textureId &&
            entry.width == source.width &&
            entry.height == source.height &&
            entry.origin == source.origin &&
            entry.alphaType == source.alphaType
        if (unchanged) return entry.image

        entry.release()
        val backendTexture = BackendTexture.makeGL(
            width = source.width,
            height = source.height,
            isMipmapped = false,
            textureId = textureId,
            textureTarget = GL_TEXTURE_2D.toInt(),
            textureFormat = GL_RGBA8.toInt(),
        )
        // Borrowed, not adopted: closing this never deletes the caller's texture.
        val image = Image.borrowTextureFrom(
            context = context,
            backendTexture = backendTexture,
            origin = source.origin,
            colorType = ColorType.RGBA_8888,
            alphaType = source.alphaType,
        )
        entry.backendTexture = backendTexture
        entry.image = image
        entry.textureId = textureId
        entry.width = source.width
        entry.height = source.height
        entry.origin = source.origin
        entry.alphaType = source.alphaType
        return image
    }

    /** Visible for tests: whether a source is currently in the scene. */
    fun isRegistered(source: ExternalGlTexture): Boolean = entries.containsKey(source)

    override fun close() {
        entries.values.forEach { it.release() }
        entries.clear()
    }
}
