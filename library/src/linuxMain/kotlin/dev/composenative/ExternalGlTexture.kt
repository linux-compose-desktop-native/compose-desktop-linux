package dev.composenative

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.SurfaceOrigin

/**
 * An OpenGL texture produced by something other than Skia, composited into the
 * Compose scene.
 *
 * This is the seam for integrating a foreign renderer — a video player, a game
 * view, or any library that draws with its own OpenGL programs — into a Compose
 * frame. libmpv is the case this was designed against, but nothing here is
 * specific to it.
 *
 * The contract, which follows the ownership rule in DESIGN.md:
 *
 * - The implementation owns the texture and its framebuffer. Skia wraps the
 *   texture without taking ownership and will never delete it. Equally, the
 *   implementation must not delete it while a frame that references it is still
 *   in flight.
 * - [render] is called once per frame, before Skia touches the surface, at the
 *   size the [ExternalTexture] node was laid out to. It may leave OpenGL state
 *   however it likes, including its own framebuffer bound; no restoration is
 *   required. This is not a courtesy — a renderer like libmpv gives no
 *   guarantees about what it leaves behind.
 * - Before each call a fixed baseline is restored: the default framebuffer is
 *   bound, scissor, blend, depth and stencil tests are disabled, texture unit 0
 *   is active with no texture bound, and the colour mask is fully open. That is
 *   what keeps one source from corrupting the next; anything beyond it the
 *   source must set for itself.
 * - Everything happens on the single thread that owns the OpenGL context.
 * - [textureId] of 0 means "nothing to show this frame", which is the correct
 *   state before the first frame has been produced.
 * - Showing one source through several [ExternalTexture] composables renders it
 *   once per frame, at the largest size requested, and each composable scales it
 *   into its own bounds.
 */
interface ExternalGlTexture {
    /** Name of the GL texture to composite, or 0 when no frame is ready. */
    val textureId: Int

    val width: Int

    val height: Int

    /**
     * Row order of the texture.
     *
     * Content rendered into a GL framebuffer is bottom-up, which is why this
     * defaults to [SurfaceOrigin.BOTTOM_LEFT] rather than the top-left origin
     * an image decoder would produce.
     */
    val origin: SurfaceOrigin get() = SurfaceOrigin.BOTTOM_LEFT

    /** Whether the texture's alpha channel should be honoured when compositing. */
    val alphaType: ColorAlphaType get() = ColorAlphaType.OPAQUE

    /**
     * Draws the next frame into the implementation's own framebuffer.
     *
     * Called once per Compose frame with the current drawable size, before the
     * scene is composited.
     */
    fun render(width: Int, height: Int)
}
