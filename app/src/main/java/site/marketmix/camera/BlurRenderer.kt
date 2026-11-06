package site.marketmix.camera

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig as KhronosConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the camera frame, a blurred copy of it and mixes both with the
 * subject mask. The recording render target receives the same picture,
 * so the exported file has the blur baked in.
 */
class BlurRenderer : GLSurfaceView.Renderer {

    @Volatile var blurAmount = 0.02f
    @Volatile var invert = false
    @Volatile var edgeSoftness = 0f
    @Volatile var rotationDeg = 90
    @Volatile var mirror = false
    @Volatile var preset = LensPreset.DEFAULT

    /** Invoked on the GL thread once the EGL context exists. */
    var onReady: (() -> Unit)? = null

    private val lock = Any()
    private var pendingFrame: Bitmap? = null
    private var pendingMask: Bitmap? = null
    private var pendingDepth: Bitmap? = null
    @Volatile private var pendingSubject = 0f
    @Volatile private var depthActive = false

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            ))
            position(0)
        }

    private var camTex = 0
    private var camW = 0
    private var camH = 0
    private var maskTex = 0
    private var depthTex = 0
    private var hasMask = false
    private var hasFrame = false
    private var hasDepth = false
    private var subjectDepth = 0f

    private var sharp: Fbo? = null
    private var small: Fbo? = null
    private var filled: Fbo? = null
    private var blurred: Fbo? = null
    private var midA: Fbo? = null
    private var midB: Fbo? = null
    private var deepA: Fbo? = null
    private var deepB: Fbo? = null
    private var finalFbo: Fbo? = null
    private var maskScratch: Fbo? = null
    private var maskSoft: Fbo? = null

    private var camProg = 0
    private var blitProg = 0
    private var plateProg = 0
    private var fillProg = 0
    private var blurProg = 0
    private var compProg = 0
    private var encProg = 0

    private var vPosition = 0
    private var vTexCoord = 0
    private var blitTexLoc = 0
    private var blitProgTexLoc = 0
    private var plateTexLoc = 0
    private var plateMaskLoc = 0
    private var fillTexLoc = 0
    private var blurTexLoc = 0
    private var blurDirLoc = 0
    private var compSharpLoc = 0
    private var compBlurLoc = 0
    private var compDeepLoc = 0
    private var compMidLoc = 0
    private var compMaskLoc = 0
    private var compRotLoc = 0
    private var compMirrorLoc = 0
    private var compInvertLoc = 0
    private var compStrengthLoc = 0
    private var compAmountLoc = 0
    private var compOutAspectLoc = 0
    private var compVignetteLoc = 0
    private var compBloomLoc = 0
    private var compFalloffLoc = 0
    private var compDesatLoc = 0
    private var compDepthLoc = 0
    private var compSubjectLoc = 0
    private var compHasDepthLoc = 0
    private var encPosition = 0
    private var encTexCoord = 0
    private var encTexLoc = 0

    private var viewW = 0
    private var viewH = 0

    private var mainDisplay: EGLDisplay? = null
    private var mainContext: EGLContext? = null
    private var mainSurface: EGLSurface? = null
    private var encoder: EncoderEgl? = null

    fun submitFrame(bitmap: Bitmap) {
        synchronized(lock) {
            pendingFrame?.recycle()
            pendingFrame = bitmap
        }
    }

    fun submitMask(bitmap: Bitmap) {
        synchronized(lock) {
            pendingMask?.recycle()
            pendingMask = bitmap
        }
    }

    fun submitDepth(bitmap: Bitmap, subject: Float) {
        synchronized(lock) {
            pendingDepth?.recycle()
            pendingDepth = bitmap
            pendingSubject = subject
            depthActive = true
        }
    }

    fun resetDepth() {
        synchronized(lock) {
            pendingDepth?.recycle()
            pendingDepth = null
            depthActive = false
        }
    }

    fun hasEncoder(): Boolean = encoder != null

    /** Called on the GL thread. */
    fun startEncoder(target: Surface): Boolean {
        val display = mainDisplay ?: EGL14.eglGetCurrentDisplay()
        val context = mainContext ?: EGL14.eglGetCurrentContext()
        if (display == null || context == null) return false
        val enc = EncoderEgl()
        if (!enc.create(target, context, display)) return false
        encoder = enc
        encoderStartNs = System.nanoTime()
        isEncoding = false
        enc.makeCurrent()
        encProg = linkProgram(VERTEX, BLIT_FRAGMENT)
        encPosition = GLES20.glGetAttribLocation(encProg, "aPos")
        encTexCoord = GLES20.glGetAttribLocation(encProg, "aUv")
        encTexLoc = GLES20.glGetUniformLocation(encProg, "uTex")
        restoreMain()
        return true
    }

    /** Called on the GL thread. */
    fun stopEncoder() {
        encoder?.release()
        encoder = null
        // The encoder program belongs to the encoder context, which is
        // destroyed above; deleting it from this context would target a
        // different object.
        encProg = 0
        isEncoding = false
        // release() detaches every context from this thread, so without the
        // restore below the compositor draws into nothing and the preview
        // keeps showing the last recorded frame.
        restoreMain()
    }

    private var isEncoding = false
    private var encoderStartNs = 0L

    override fun onSurfaceCreated(gl: GL10?, config: KhronosConfig?) {
        mainDisplay = EGL14.eglGetCurrentDisplay()
        mainContext = EGL14.eglGetCurrentContext()
        mainSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)

        releaseGl()

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        camProg = linkProgram(VERTEX, CAMERA_FRAGMENT)
        vPosition = GLES20.glGetAttribLocation(camProg, "aPos")
        vTexCoord = GLES20.glGetAttribLocation(camProg, "aUv")
        blitTexLoc = GLES20.glGetUniformLocation(camProg, "uTex")

        blitProg = linkProgram(VERTEX, BLIT_FRAGMENT)
        blitProgTexLoc = GLES20.glGetUniformLocation(blitProg, "uTex")
        plateProg = linkProgram(VERTEX, PLATE_FRAGMENT)
        plateTexLoc = GLES20.glGetUniformLocation(plateProg, "uTex")
        plateMaskLoc = GLES20.glGetUniformLocation(plateProg, "uMask")
        fillProg = linkProgram(VERTEX, FILL_FRAGMENT)
        fillTexLoc = GLES20.glGetUniformLocation(fillProg, "uTex")
        blurProg = linkProgram(VERTEX, BLUR_FRAGMENT)
        blurTexLoc = GLES20.glGetUniformLocation(blurProg, "uTex")
        blurDirLoc = GLES20.glGetUniformLocation(blurProg, "uDir")

        compProg = linkProgram(VERTEX, COMPOSITE_FRAGMENT)
        compSharpLoc = GLES20.glGetUniformLocation(compProg, "uSharp")
        compBlurLoc = GLES20.glGetUniformLocation(compProg, "uBlur")
        compDeepLoc = GLES20.glGetUniformLocation(compProg, "uDeep")
        compMidLoc = GLES20.glGetUniformLocation(compProg, "uMid")
        compMaskLoc = GLES20.glGetUniformLocation(compProg, "uMask")
        compRotLoc = GLES20.glGetUniformLocation(compProg, "uRot")
        compMirrorLoc = GLES20.glGetUniformLocation(compProg, "uMirror")
        compInvertLoc = GLES20.glGetUniformLocation(compProg, "uInvert")
        compStrengthLoc = GLES20.glGetUniformLocation(compProg, "uStrength")
        compAmountLoc = GLES20.glGetUniformLocation(compProg, "uAmount")
        compOutAspectLoc = GLES20.glGetUniformLocation(compProg, "uOutAspect")
        compVignetteLoc = GLES20.glGetUniformLocation(compProg, "uVignette")
        compBloomLoc = GLES20.glGetUniformLocation(compProg, "uBloom")
        compFalloffLoc = GLES20.glGetUniformLocation(compProg, "uFalloff")
        compDesatLoc = GLES20.glGetUniformLocation(compProg, "uDesat")
        compDepthLoc = GLES20.glGetUniformLocation(compProg, "uDepth")
        compSubjectLoc = GLES20.glGetUniformLocation(compProg, "uSubject")
        compHasDepthLoc = GLES20.glGetUniformLocation(compProg, "uHasDepth")

        camTex = newTexture()
        maskTex = newTexture()
        depthTex = newTexture()
        val black = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt()) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, black, 0)
        black.recycle()
        // Start with a fully white mask so the preview is sharp until the
        // first segmentation result arrives.
        val white = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, white, 0)
        white.recycle()
        sharp = createFbo(CAM_W, CAM_H)
        small = createFbo(SMALL_W, SMALL_H)
        filled = createFbo(SMALL_W, SMALL_H)
        blurred = createFbo(SMALL_W, SMALL_H)
        midA = createFbo(MID_W, MID_H)
        midB = createFbo(MID_W, MID_H)
        deepA = createFbo(DEEP_W, DEEP_H)
        deepB = createFbo(DEEP_W, DEEP_H)
        finalFbo = createFbo(OUT_W, OUT_H)
        maskScratch = createFbo(MASK_RES, MASK_RES)
        maskSoft = createFbo(MASK_RES, MASK_RES)

        hasFrame = false
        hasMask = false
        hasDepth = false
        onReady?.invoke()
    }

    private fun releaseGl() {
        listOf(sharp, small, filled, blurred, midA, midB, deepA, deepB, finalFbo, maskScratch, maskSoft)
            .forEach { fbo ->
                fbo ?: return@forEach
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo.fbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fbo.tex), 0)
            }
        sharp = null
        small = null
        filled = null
        blurred = null
        midA = null
        midB = null
        deepA = null
        deepB = null
        finalFbo = null
        maskScratch = null
        maskSoft = null
        if (camTex != 0) GLES20.glDeleteTextures(1, intArrayOf(camTex), 0)
        if (maskTex != 0) GLES20.glDeleteTextures(1, intArrayOf(maskTex), 0)
        if (depthTex != 0) GLES20.glDeleteTextures(1, intArrayOf(depthTex), 0)
        camTex = 0
        maskTex = 0
        depthTex = 0
        hasDepth = false
        camW = 0
        camH = 0
        listOf(camProg, blitProg, plateProg, fillProg, blurProg, compProg).forEach { prog ->
            if (prog != 0) GLES20.glDeleteProgram(prog)
        }
        camProg = 0
        blitProg = 0
        plateProg = 0
        fillProg = 0
        blurProg = 0
        compProg = 0
        encProg = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        var frameUpdated = false
        var frame: Bitmap? = null
        var mask: Bitmap? = null
        var depth: Bitmap? = null
        synchronized(lock) {
            frame = pendingFrame
            pendingFrame = null
            mask = pendingMask
            pendingMask = null
            depth = pendingDepth
            pendingDepth = null
            if (depthActive) {
                hasDepth = true
                subjectDepth = pendingSubject
            } else {
                hasDepth = false
            }
        }
        frame?.let {
            uploadCamera(it)
            it.recycle()
            hasFrame = true
            frameUpdated = true
        }
        mask?.let {
            uploadMask(it)
            it.recycle()
            hasMask = true
        }
        depth?.let {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            it.recycle()
        }

        if (!hasFrame) {
            GLES20.glViewport(0, 0, viewW, viewH)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        renderScene()
        drawToScreen()
        if (frameUpdated && encoder != null) drawToEncoder()
    }

    private fun uploadCamera(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, camTex)
        if (bitmap.width != camW || bitmap.height != camH) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            camW = bitmap.width
            camH = bitmap.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    private fun uploadMask(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /**
     * How much of the defocused background shows through. Below a quarter of
     * the dial the backdrop fades in so the smallest steps are still gentle;
     * past that the background is fully defocused and the octave mix decides
     * how wide the glow is. Blending a half-strength blur over a sharp copy
     * would read as a double image, which no lens produces.
     */
    private fun strength(): Float =
        if (blurAmount <= 0f) 0f else minOf(1f, blurAmount / STRENGTH_KNEE)

    private fun renderScene() {
        val sharpFbo = sharp ?: return
        val smallFbo = small ?: return
        val filledFbo = filled ?: return
        val blurFbo = blurred ?: return
        val midAFbo = midA ?: return
        val midBFbo = midB ?: return
        val deepAFbo = deepA ?: return
        val deepBFbo = deepB ?: return
        val finalTarget = finalFbo ?: return

        val lens = preset
        val amount = blurAmount
        val aspect = if (camH > 0) camW.toFloat() / camH else CAM_W.toFloat() / CAM_H
        // The matte stays close to the silhouette at every blur strength. A
        // wide blend mixes the sharp *background* back over the defocused one,
        // which at full blur draws a visible band between the subject and the
        // blur; the mask already arrives with a soft edge from upsampling.
        val feather = edgeSoftness * FEATHER_MAX
        renderMask(aspect, feather)

        // Sharp camera frame, stored top-down in the target.
        bind(sharpFbo)
        GLES20.glViewport(0, 0, sharpFbo.w, sharpFbo.h)
        GLES20.glUseProgram(camProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, camTex)
        GLES20.glUniform1i(blitTexLoc, 0)
        drawQuad(vPosition, vTexCoord)

        // Three shrinking levels. Blurring where the texels are already large
        // gives a round, even defocus; one wide kernel on a single buffer smears
        // into visible streaks and leaves detail behind.
        plateInto(smallFbo, sharpFbo)
        // The plate has a hole where the subject stands, which the pixels
        // beside it have to fill before the blur reads them: an empty hole
        // makes the levels next to the silhouette fall back to a sharper
        // octave and outlines the cut as a ring.
        fillInto(filledFbo, smallFbo.tex)
        blurInto(blurFbo, filledFbo.tex, true, 0.8f + amount * 3.0f)
        blurInto(smallFbo, blurFbo.tex, false, 0.8f + amount * 3.0f)

        blitInto(midAFbo, smallFbo.tex)
        blurInto(midBFbo, midAFbo.tex, true, 1.7f)
        blurInto(midAFbo, midBFbo.tex, false, 1.7f)

        blitInto(deepAFbo, midAFbo.tex)
        blurInto(deepBFbo, deepAFbo.tex, true, 1.7f)
        blurInto(deepAFbo, deepBFbo.tex, false, 1.7f)

        // Composite subject + defocused background.
        bind(finalTarget)
        GLES20.glViewport(0, 0, OUT_W, OUT_H)
        GLES20.glUseProgram(compProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sharpFbo.tex)
        GLES20.glUniform1i(compSharpLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (hasMask && amount > 0f) smallFbo.tex else sharpFbo.tex)
        GLES20.glUniform1i(compBlurLoc, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (hasMask) maskSoft?.tex ?: maskTex else maskTex)
        GLES20.glUniform1i(compMaskLoc, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (hasMask && amount > 0f) deepAFbo.tex else sharpFbo.tex)
        GLES20.glUniform1i(compDeepLoc, 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (hasMask && amount > 0f) midAFbo.tex else sharpFbo.tex)
        GLES20.glUniform1i(compMidLoc, 4)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
        GLES20.glUniform1i(compDepthLoc, 5)
        GLES20.glUniform1f(compSubjectLoc, subjectDepth)
        GLES20.glUniform1f(compHasDepthLoc, if (hasDepth && hasMask) 1f else 0f)
        GLES20.glUniform1f(compRotLoc, Math.toRadians(rotationDeg.toDouble()).toFloat())
        GLES20.glUniform1f(compMirrorLoc, if (mirror) 1f else 0f)
        GLES20.glUniform1f(compInvertLoc, if (invert) 1f else 0f)
        GLES20.glUniform1f(compStrengthLoc, strength())
        GLES20.glUniform1f(compAmountLoc, amount)
        GLES20.glUniform1f(compOutAspectLoc, OUT_W.toFloat() / OUT_H)
        GLES20.glUniform1f(compVignetteLoc, lens.vignette)
        GLES20.glUniform1f(compBloomLoc, lens.bloom)
        GLES20.glUniform1f(compFalloffLoc, lens.falloff)
        GLES20.glUniform1f(compDesatLoc, lens.desat)
        drawQuad(vPosition, vTexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun plateInto(target: Fbo, source: Fbo) {
        bind(target)
        GLES20.glViewport(0, 0, target.w, target.h)
        GLES20.glUseProgram(plateProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.tex)
        GLES20.glUniform1i(plateTexLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glUniform1i(plateMaskLoc, 1)
        drawQuad(vPosition, vTexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun fillInto(target: Fbo, source: Int) {
        bind(target)
        GLES20.glViewport(0, 0, target.w, target.h)
        GLES20.glUseProgram(fillProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
        GLES20.glUniform1i(fillTexLoc, 0)
        drawQuad(vPosition, vTexCoord)
    }

    private fun blitInto(target: Fbo, source: Int) {
        bind(target)
        GLES20.glViewport(0, 0, target.w, target.h)
        GLES20.glUseProgram(blitProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
        GLES20.glUniform1i(blitProgTexLoc, 0)
        drawQuad(vPosition, vTexCoord)
    }

    private fun blurInto(target: Fbo, source: Int, horizontal: Boolean, texels: Float) {
        bind(target)
        GLES20.glViewport(0, 0, target.w, target.h)
        GLES20.glUseProgram(blurProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
        GLES20.glUniform1i(blurTexLoc, 0)
        if (horizontal) {
            GLES20.glUniform2f(blurDirLoc, texels / target.w, 0f)
        } else {
            GLES20.glUniform2f(blurDirLoc, 0f, texels / target.h)
        }
        drawQuad(vPosition, vTexCoord)
    }

    /**
     * Softens the matte where the cut is made.
     *
     * The matte arrives at a quarter of the frame width and stretched to a
     * square, so one texel covers more horizontally than vertically; the two
     * passes correct for that so the cut is the same width in every direction.
     */
    private fun renderMask(aspect: Float, feather: Float) {
        if (!hasMask) return
        val scratch = maskScratch ?: return
        val soft = maskSoft ?: return

        blurPass(scratch, maskTex, feather / MASK_RES, 0f)
        blurPass(soft, scratch.tex, 0f, feather * aspect / MASK_RES)
    }

    private fun blurPass(target: Fbo, source: Int, dx: Float, dy: Float) {
        bind(target)
        GLES20.glViewport(0, 0, target.w, target.h)
        GLES20.glUseProgram(blurProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
        GLES20.glUniform1i(blurTexLoc, 0)
        GLES20.glUniform2f(blurDirLoc, dx, dy)
        drawQuad(vPosition, vTexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawToScreen() {
        val finalTarget = finalFbo ?: return
        if (viewW == 0 || viewH == 0) return
        // The composite pass leaves the final target bound. Sampling it while
        // it is still the draw target is undefined, and the window surface
        // never receives a pixel, so the preview stays black.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val scale = minOf(viewW.toFloat() / OUT_W, viewH.toFloat() / OUT_H)
        val w = (OUT_W * scale).toInt().coerceAtLeast(1)
        val h = (OUT_H * scale).toInt().coerceAtLeast(1)
        val x = (viewW - w) / 2
        val y = (viewH - h) / 2
        GLES20.glViewport(x, y, w, h)
        GLES20.glUseProgram(blitProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTarget.tex)
        GLES20.glUniform1i(blitProgTexLoc, 0)
        drawQuad(vPosition, vTexCoord)
    }

    private fun drawToEncoder() {
        val enc = encoder ?: return
        val finalTarget = finalFbo ?: return
        if (encProg == 0) return
        // The encoder texture lives in a shared context; flush the scene that
        // was just rendered before switching contexts.
        GLES20.glFlush()
        enc.makeCurrent()
        GLES20.glViewport(0, 0, OUT_W, OUT_H)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(encProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTarget.tex)
        GLES20.glUniform1i(encTexLoc, 0)
        GLES20.glEnableVertexAttribArray(encPosition)
        GLES20.glEnableVertexAttribArray(encTexCoord)
        quad.position(0)
        GLES20.glVertexAttribPointer(encPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(encTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(encPosition)
        GLES20.glDisableVertexAttribArray(encTexCoord)
        enc.setPresentationTime(System.nanoTime() - encoderStartNs)
        enc.swap()
        restoreMain()
    }

    private fun restoreMain() {
        val display = mainDisplay ?: return
        val surface = mainSurface ?: return
        val context = mainContext ?: return
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun drawQuad(posLoc: Int, uvLoc: Int) {
        quad.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun newTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createFbo(w: Int, h: Int): Fbo {
        val tex = newTexture()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return Fbo(tex, ids[0], w, h)
    }

    private fun bind(fbo: Fbo) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.fbo)
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }

    private fun linkProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private class Fbo(val tex: Int, val fbo: Int, val w: Int, val h: Int)

    companion object {
        const val CAM_W = 1280
        const val CAM_H = 720
        const val OUT_W = 720
        const val OUT_H = 1280
        const val SMALL_W = 320
        const val SMALL_H = 180
        const val MID_W = 160
        const val MID_H = 90
        const val DEEP_W = 80
        const val DEEP_H = 45
        const val MASK_RES = 256

        /** Matte feather, in mask texels of frame width, at full smoothness. */
        private const val FEATHER_MAX = 2f

        /** Part of the dial over which the backdrop fades in from sharp. */
        private const val STRENGTH_KNEE = 0.3f

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = aPos;
            }
        """

        private const val CAMERA_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y));
            }
        """

        private const val BLIT_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uTex, vUv);
            }
        """

        /**
         * First stop of the blur pyramid. Carries the subject out of the
         * background as coverage so the blur can average only what is behind
         * the person; otherwise their own colour smears into a halo around
         * them. Colour is stored premultiplied, which is what makes a plain
         * weighted sum behave at the silhouette: skin is multiplied by zero
         * coverage before it can be filtered.
         *
         * The cut is pushed slightly outwards and made hard: the pixels on the
         * silhouette are a mix of skin and background, and letting those count
         * as background is enough to tint the whole halo.
         */
        private const val PLATE_FRAGMENT = """
            precision highp float;
            uniform sampler2D uTex;
            uniform sampler2D uMask;
            varying vec2 vUv;
            const float MPX = 0.005859375;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                float y = 1.0 - vUv.y;
                float m = texture2D(uMask, vec2(vUv.x, y)).r;
                m = max(m, texture2D(uMask, vec2(vUv.x + MPX, y)).r);
                m = max(m, texture2D(uMask, vec2(vUv.x - MPX, y)).r);
                m = max(m, texture2D(uMask, vec2(vUv.x, y + MPX)).r);
                m = max(m, texture2D(uMask, vec2(vUv.x, y - MPX)).r);
                float a = m > 0.5 ? 0.0 : 1.0;
                gl_FragColor = vec4(c.rgb * a, a);
            }
        """

        /**
         * Fills the plate's hole from the background around it so every level
         * of the pyramid keeps full coverage up to the silhouette. Without it
         * the levels lose coverage inside the cut and the composite falls back
         * to a sharper octave in a ring around the subject, which at full blur
         * reads as a gap between the person and their defocused surroundings.
         *
         * The taps step out until they clear the hole; weights fall off with
         * the square of coverage so partly covered pixels barely contribute.
         */
        private const val FILL_FRAGMENT = """
            precision highp float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            const vec2 TEXEL = vec2(0.003125, 0.005555556);
            void main() {
                vec4 c = texture2D(uTex, vUv);
                if (c.a < 0.995) {
                    vec3 sum = vec3(0.0);
                    float w = 0.0;
                    vec4 s;
                    #define TAP(dx, dy) s = texture2D(uTex, vUv + vec2(dx, dy) * TEXEL); w += s.a * s.a; sum += s.rgb * s.a;
                    TAP(6.0, 0.0) TAP(-6.0, 0.0) TAP(0.0, 6.0) TAP(0.0, -6.0)
                    TAP(14.0, 0.0) TAP(-14.0, 0.0) TAP(0.0, 14.0) TAP(0.0, -14.0)
                    TAP(26.0, 0.0) TAP(-26.0, 0.0) TAP(0.0, 26.0) TAP(0.0, -26.0)
                    TAP(40.0, 0.0) TAP(-40.0, 0.0) TAP(0.0, 40.0) TAP(0.0, -40.0)
                    TAP(10.0, 10.0) TAP(-10.0, 10.0) TAP(10.0, -10.0) TAP(-10.0, -10.0)
                    TAP(24.0, 24.0) TAP(-24.0, 24.0) TAP(24.0, -24.0) TAP(-24.0, -24.0)
                    #undef TAP
                    if (w > 0.001) c = vec4(sum / w, 1.0);
                }
                gl_FragColor = c;
            }
        """

        /**
         * Separable Gaussian over premultiplied colour, so the matte's subject
         * never bleeds into the background. The matte passes through here with
         * alpha 1, where this is the same weighted sum as before.
         */
        private const val BLUR_FRAGMENT = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            varying vec2 vUv;
            void main() {
                vec4 c = texture2D(uTex, vUv) * 0.227027;
                c += texture2D(uTex, vUv + uDir * 1.3846) * 0.316216;
                c += texture2D(uTex, vUv - uDir * 1.3846) * 0.316216;
                c += texture2D(uTex, vUv + uDir * 3.2308) * 0.070270;
                c += texture2D(uTex, vUv - uDir * 3.2308) * 0.070270;
                gl_FragColor = c;
            }
        """

        private const val COMPOSITE_FRAGMENT = """
            precision highp float;
            uniform sampler2D uSharp;
            uniform sampler2D uBlur;
            uniform sampler2D uMid;
            uniform sampler2D uDeep;
            uniform sampler2D uMask;
            uniform float uRot;
            uniform float uMirror;
            uniform float uInvert;
            uniform float uOutAspect;
            uniform float uStrength;
            uniform float uAmount;
            uniform float uVignette;
            uniform float uBloom;
            uniform float uFalloff;
            uniform float uDesat;
            uniform sampler2D uDepth;
            uniform float uSubject;
            uniform float uHasDepth;
            varying vec2 vUv;
            void main() {
                vec2 outTopDown = vec2(vUv.x, 1.0 - vUv.y);
                vec2 p = outTopDown - 0.5;
                float a = -uRot;
                float c = cos(a);
                float s = sin(a);
                vec2 q = vec2(c * p.x - s * p.y, s * p.x + c * p.y) + 0.5;
                if (uMirror > 0.5) q.x = 1.0 - q.x;
                vec3 sharp = texture2D(uSharp, vec2(q.x, 1.0 - q.y)).rgb;

                // The matte is cut where the person is, so the background
                // meets the silhouette instead of retreating behind it. An
                // eroded matte leaves a band of defocused background in front
                // of the subject, which at full blur reads as a gap between
                // the two.
                float m = texture2D(uMask, q).r;
                // The matte carries a thin rim of background around the person.
                // Kept sharp it reads as a clear, bright margin; half a texel of
                // erosion hands that rim back to the defocused background so the
                // blur meets the subject.
                m = min(m, texture2D(uMask, q + vec2(0.001953125, 0.0)).r);
                m = min(m, texture2D(uMask, q - vec2(0.001953125, 0.0)).r);
                m = min(m, texture2D(uMask, q + vec2(0.0, 0.001953125)).r);
                m = min(m, texture2D(uMask, q - vec2(0.0, 0.001953125)).r);
                if (uInvert > 0.5) m = 1.0 - m;
                // The matte arrives 256 across and is upsampled, so its rim is
                // several pixels wide. Inside that band the sample is the sharp
                // background rather than the subject, and mixing it over the
                // defocused background leaves a pale, see-through margin round
                // the person. Steepening the rim keeps the transition on the
                // silhouette and lets the subject meet its own edge.
                m = smoothstep(0.40, 0.60, m);

                // Depth separates front from back: anything nearer than the
                // subject is foreground and stays sharp; only what is behind
                // the focus plane is allowed to defocus. A close subject sits
                // around 0.9 of the frame depth, so the band is a share of the
                // headroom left above it; fixed offsets would run past 1.0 and
                // protect nothing. The ramp is centred a hair above the
                // subject's median, so noise just behind it still blurs while
                // the nearest foreground is fully sharp.
                if (uHasDepth > 0.5) {
                    float d = texture2D(uDepth, q).r;
                    float head = max(1.0 - uSubject, 0.02);
                    float fg = smoothstep(uSubject - 0.05 * head, uSubject + 0.10 * head, d);
                    m = max(m, fg);
                }

                // Outside the in-focus band a real lens keeps going soft, so the
                // corners get more of the deepest level than the middle does.
                vec2 fromCentre = vec2(p.x * uOutAspect, p.y) * 2.0;
                float edge = smoothstep(0.45, 1.10, length(fromCentre));
                // Each octave fades in over its own range of the dial, so
                // turning the blur up widens the glow instead of just
                // stretching the same small kernel into streaks. The chain is
                // a convex blend, which keeps the ramp monotone.
                float midMix = smoothstep(0.25, 0.75, uAmount);
                float deepMix = smoothstep(0.60, 0.97, uAmount);
                deepMix = clamp(deepMix * (1.0 + uFalloff * edge), 0.0, 1.0);

                // The pyramid is premultiplied by background coverage, so undo
                // that here. Where a level has no background behind the subject
                // it inherits the softer level, which always has some.
                vec4 nearTex = texture2D(uBlur, vec2(q.x, 1.0 - q.y));
                vec4 midTex = texture2D(uMid, vec2(q.x, 1.0 - q.y));
                vec4 deepTex = texture2D(uDeep, vec2(q.x, 1.0 - q.y));
                vec3 blurNear = nearTex.a > 0.02 ? nearTex.rgb / nearTex.a : sharp;
                vec3 blurMid = midTex.a > 0.02 ? midTex.rgb / midTex.a : blurNear;
                vec3 blurDeep = deepTex.a > 0.02 ? deepTex.rgb / deepTex.a : blurMid;
                vec3 blur = mix(blurNear, blurMid, midMix);
                blur = mix(blur, blurDeep, deepMix);

                // Long glass dims the corners, drains a little colour out of the
                // far field and lets highlights swell into discs.
                blur *= 1.0 - uVignette * edge;
                float grey = dot(blur, vec3(0.299, 0.587, 0.114));
                blur = mix(blur, vec3(grey), uDesat * deepMix);
                blur += max(blur - 0.72, 0.0) * uBloom;

                float reveal = clamp(uStrength * (1.0 + 0.15 * uFalloff * edge), 0.0, 1.0);
                vec3 bg = mix(sharp, blur, reveal);
                gl_FragColor = vec4(mix(bg, sharp, m), 1.0);
            }
        """
    }
}

/** Offscreen EGL surface that feeds a MediaCodec input surface. */
private class EncoderEgl {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun create(target: Surface, share: EGLContext, shareDisplay: EGLDisplay): Boolean {
        display = shareDisplay
        val config = chooseConfig(display) ?: return false
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, share, ctxAttrs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        return surface != EGL14.EGL_NO_SURFACE
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val base = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT
        )
        val recordable = base + intArrayOf(RECORDABLE, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (EGL14.eglChooseConfig(display, recordable, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            return configs[0]
        }
        val plain = base + intArrayOf(EGL14.EGL_NONE)
        if (EGL14.eglChooseConfig(display, plain, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            return configs[0]
        }
        return null
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    fun setPresentationTime(ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, ns)
    }

    fun swap() {
        EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val RECORDABLE = 0x3142
    }
}
