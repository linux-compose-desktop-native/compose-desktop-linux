package dev.composenative.demo

import dev.composenative.ExternalGlTexture
import dev.composenative.interop.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

/**
 * A stand-in for a real foreign renderer such as libmpv.
 *
 * It owns its framebuffer and texture and draws with plain OpenGL, pulsing so it
 * is visibly live rather than a static image. Deliberately does not restore GL
 * state afterwards: the contract says it does not have to.
 */
@OptIn(ExperimentalForeignApi::class)
class CheckerTexture(private val argb: Long) : ExternalGlTexture, AutoCloseable {
    private var framebuffer = 0u
    private var texture = 0u
    private var frame = 0

    override var width = 0
        private set

    override var height = 0
        private set

    override val textureId: Int get() = texture.toInt()

    override fun render(width: Int, height: Int) {
        ensureTarget(width, height)
        frame++

        // Pulse between half and full brightness so the texture is obviously live.
        val pulse = 0.5f + 0.5f * kotlin.math.sin(frame / 30f)
        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), framebuffer)
        glViewport(0, 0, width, height)
        glClearColor(
            ((argb shr 16) and 0xFF) / 255f * pulse,
            ((argb shr 8) and 0xFF) / 255f * pulse,
            (argb and 0xFF) / 255f * pulse,
            1f,
        )
        glClear(GL_COLOR_BUFFER_BIT.toUInt())
    }

    private fun ensureTarget(width: Int, height: Int) = memScoped {
        if (width <= 0 || height <= 0 ||
            (this@CheckerTexture.width == width && this@CheckerTexture.height == height)
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
        this@CheckerTexture.width = width
        this@CheckerTexture.height = height

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
            "Could not create the demo off-screen framebuffer"
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
