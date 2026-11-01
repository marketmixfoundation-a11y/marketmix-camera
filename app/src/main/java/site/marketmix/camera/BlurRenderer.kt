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

    /** Invoked on the GL thread once the EGL context exists. */
    var onReady: (() -> Unit)? = null

    private val lock = Any()
    private var pendingFrame: Bitmap? = null
    private var pendingMask: Bitmap? = null

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
    private var hasMask = false
    private var hasFrame = false

    private var sharp: Fbo? = null
    private var small: Fbo? = null
    private var blurred: Fbo? = null
    private var finalFbo: Fbo? = null

    private var camProg = 0
    private var blitProg = 0
    private var blurProg = 0
    private var compProg = 0
    private var encProg = 0

    private var vPosition = 0
    private var vTexCoord = 0
    private var blitTexLoc = 0
    private var blurTexLoc = 0
    private var blurDirLoc = 0
    private var compSharpLoc = 0
    private var compBlurLoc = 0
    private var compMaskLoc = 0
    private var compRotLoc = 0
    private var compMirrorLoc = 0
    private var compInvertLoc = 0
    private var compSoftLoc = 0
    private var compStrengthLoc = 0
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
        blurProg = linkProgram(VERTEX, BLUR_FRAGMENT)
        blurTexLoc = GLES20.glGetUniformLocation(blurProg, "uTex")
        blurDirLoc = GLES20.glGetUniformLocation(blurProg, "uDir")

        compProg = linkProgram(VERTEX, COMPOSITE_FRAGMENT)
        compSharpLoc = GLES20.glGetUniformLocation(compProg, "uSharp")
        compBlurLoc = GLES20.glGetUniformLocation(compProg, "uBlur")
        compMaskLoc = GLES20.glGetUniformLocation(compProg, "uMask")
        compRotLoc = GLES20.glGetUniformLocation(compProg, "uRot")
        compMirrorLoc = GLES20.glGetUniformLocation(compProg, "uMirror")
        compInvertLoc = GLES20.glGetUniformLocation(compProg, "uInvert")
        compSoftLoc = GLES20.glGetUniformLocation(compProg, "uSoft")
        compStrengthLoc = GLES20.glGetUniformLocation(compProg, "uStrength")

        camTex = newTexture()
        maskTex = newTexture()
        // Start with a fully white mask so the preview is sharp until the
        // first segmentation result arrives.
        val white = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, white, 0)
        white.recycle()
        sharp = createFbo(CAM_W, CAM_H)
        small = createFbo(SMALL_W, SMALL_H)
        blurred = createFbo(SMALL_W, SMALL_H)
        finalFbo = createFbo(OUT_W, OUT_H)

        hasFrame = false
        hasMask = false
        onReady?.invoke()
    }

    private fun releaseGl() {
        listOf(sharp, small, blurred, finalFbo).forEach { fbo ->
            fbo ?: return@forEach
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo.fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fbo.tex), 0)
        }
        sharp = null
        small = null
        blurred = null
        finalFbo = null
        if (camTex != 0) GLES20.glDeleteTextures(1, intArrayOf(camTex), 0)
        if (maskTex != 0) GLES20.glDeleteTextures(1, intArrayOf(maskTex), 0)
        camTex = 0
        maskTex = 0
        camW = 0
        camH = 0
        listOf(camProg, blitProg, blurProg, compProg).forEach { prog ->
            if (prog != 0) GLES20.glDeleteProgram(prog)
        }
        camProg = 0
        blitProg = 0
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
        synchronized(lock) {
            frame = pendingFrame
            pendingFrame = null
            mask = pendingMask
            pendingMask = null
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
     * How much of the soft background shows through. The blur buffer is a
     * quarter of the frame, so the curve keeps the low steps from looking
     * like the full-strength blur.
     */
    private fun strength(): Float =
        if (blurAmount <= 0f) 0f else Math.pow(blurAmount.toDouble(), 2.3).toFloat()

    private fun renderScene() {
        val sharpFbo = sharp ?: return
        val smallFbo = small ?: return
        val blurFbo = blurred ?: return
        val finalTarget = finalFbo ?: return

        // Sharp camera frame, stored top-down in the target.
        bind(sharpFbo)
        GLES20.glViewport(0, 0, sharpFbo.w, sharpFbo.h)
        GLES20.glUseProgram(camProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, camTex)
        GLES20.glUniform1i(blitTexLoc, 0)
        drawQuad(vPosition, vTexCoord)

        // Downscale, then separable blur.
        bind(smallFbo)
        GLES20.glViewport(0, 0, SMALL_W, SMALL_H)
        GLES20.glUseProgram(blitProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sharpFbo.tex)
        GLES20.glUniform1i(blitTexLoc, 0)
        drawQuad(vPosition, vTexCoord)

        val radius = 0.8f + blurAmount * 5.5f
        bind(blurFbo)
        GLES20.glViewport(0, 0, SMALL_W, SMALL_H)
        GLES20.glUseProgram(blurProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smallFbo.tex)
        GLES20.glUniform1i(blurTexLoc, 0)
        GLES20.glUniform2f(blurDirLoc, radius / SMALL_W, 0f)
        drawQuad(vPosition, vTexCoord)

        // Rotate the pass: the second (vertical) blur reads `blurred` and
        // lands back in `small`, which is what the composite samples.
        bind(smallFbo)
        GLES20.glViewport(0, 0, SMALL_W, SMALL_H)
        GLES20.glUseProgram(blurProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurFbo.tex)
        GLES20.glUniform1i(blurTexLoc, 0)
        GLES20.glUniform2f(blurDirLoc, 0f, radius / SMALL_H)
        drawQuad(vPosition, vTexCoord)

        // Composite subject + blurred background.
        bind(finalTarget)
        GLES20.glViewport(0, 0, OUT_W, OUT_H)
        GLES20.glUseProgram(compProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sharpFbo.tex)
        GLES20.glUniform1i(compSharpLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (hasMask && blurAmount > 0f) smallFbo.tex else sharpFbo.tex)
        GLES20.glUniform1i(compBlurLoc, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glUniform1i(compMaskLoc, 2)
        GLES20.glUniform1f(compRotLoc, Math.toRadians(rotationDeg.toDouble()).toFloat())
        GLES20.glUniform1f(compMirrorLoc, if (mirror) 1f else 0f)
        GLES20.glUniform1f(compInvertLoc, if (invert) 1f else 0f)
        GLES20.glUniform1f(compSoftLoc, edgeSoftness)
        GLES20.glUniform1f(compStrengthLoc, strength())
        drawQuad(vPosition, vTexCoord)
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
        GLES20.glUniform1i(blitTexLoc, 0)
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

        private const val BLUR_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            varying vec2 vUv;
            void main() {
                vec3 c = texture2D(uTex, vUv).rgb * 0.227027;
                c += texture2D(uTex, vUv + uDir * 1.3846).rgb * 0.316216;
                c += texture2D(uTex, vUv - uDir * 1.3846).rgb * 0.316216;
                c += texture2D(uTex, vUv + uDir * 3.2308).rgb * 0.070270;
                c += texture2D(uTex, vUv - uDir * 3.2308).rgb * 0.070270;
                gl_FragColor = vec4(c, 1.0);
            }
        """

        private const val COMPOSITE_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uSharp;
            uniform sampler2D uBlur;
            uniform sampler2D uMask;
            uniform float uRot;
            uniform float uMirror;
            uniform float uInvert;
            uniform float uSoft;
            uniform float uStrength;
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
                vec3 blur = texture2D(uBlur, vec2(q.x, 1.0 - q.y)).rgb;
                // The blurred copy always comes from a 320x180 buffer, so it is
                // far softer than any amount the low end of the scale should
                // show; the background is mixed between the two instead.
                vec3 bg = mix(sharp, blur, uStrength);
                float m = texture2D(uMask, q).r;
                // uSoft slides the sample spread and the transition band, so
                // at 0 the taps collapse onto the centre and the ramp is the
                // plain cut.
                float t = 0.01171875 * uSoft;
                m += texture2D(uMask, q + vec2(t, 0.0)).r;
                m += texture2D(uMask, q - vec2(t, 0.0)).r;
                m += texture2D(uMask, q + vec2(0.0, t)).r;
                m += texture2D(uMask, q - vec2(0.0, t)).r;
                m *= 0.2;
                if (uInvert > 0.5) m = 1.0 - m;
                float band = mix(0.15, 0.45, uSoft);
                m = smoothstep(0.5 - band, 0.5 + band, m);
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
