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
 * - [render] runs before Skia touches the surface and may leave OpenGL state
 *   however it likes, including its own framebuffer bound. The renderer resets
 *   Skia's cached view of the context afterwards, so no restoration is
 *   required. This is not a courtesy: a renderer like libmpv gives no
 *   guarantees about the state it leaves behind.
 * - Everything happens on the single thread that owns the OpenGL context.
 * - [textureId] of 0 means "nothing to show this frame", which is the correct
 *   state before the first frame has been produced.
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
