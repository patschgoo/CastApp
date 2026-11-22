package io.github.patschgoo.castapp.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Copies frames from an input [Surface] (screen capture or a video decoder) to an
 * output surface (the H.264 encoder) with OpenGL, scaling them to fit or fill
 * the output. All GL work happens on a private thread.
 */
class GlScaler(
    private val output: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    @Volatile var fill: Boolean,
) {
    private val thread = HandlerThread("gl-scaler").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    @Volatile private var inWidth = 1
    @Volatile private var inHeight = 1
    @Volatile private var released = false

    /** Number of frames drawn so far (also lets callers wait for the first frame). */
    @Volatile var framesDrawn = 0L
        private set
    private val frameLock = java.util.concurrent.locks.ReentrantLock()
    private val frameDrawn = frameLock.newCondition()

    /** Waits until a frame after [count] has been drawn (used to convert video without dropping frames). */
    fun awaitFrameAfter(count: Long, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        frameLock.lock()
        try {
            while (framesDrawn <= count && !released) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                frameDrawn.await(left, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } finally {
            frameLock.unlock()
        }
    }

    /** Sets up GL and returns the surface to render into. Blocks until ready. */
    fun start(inputWidth: Int, inputHeight: Int): Surface {
        inWidth = inputWidth
        inHeight = inputHeight
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                setUp()
            } catch (t: Throwable) {
                error = t
            }
            ready.countDown()
        }
        if (!ready.await(5, TimeUnit.SECONDS)) throw IllegalStateException("GL setup timed out")
        error?.let { throw IllegalStateException("GL setup failed", it) }
        return inputSurface!!
    }

    /** Changes the size of the incoming frames (e.g. after the phone rotated). */
    fun setInputSize(width: Int, height: Int) {
        handler.post {
            inWidth = width
            inHeight = height
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                inputSurface?.release()
                surfaceTexture?.release()
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, eglSurface)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
            thread.quitSafely()
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(inWidth, inHeight)
        st.setOnFrameAvailableListener({ draw() }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun draw() {
        val st = surfaceTexture ?: return
        if (released) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            val inAspect = inWidth.toFloat() / inHeight
            val outAspect = outWidth.toFloat() / outHeight
            var sx = 1f
            var sy = 1f
            if (fill) {
                // Cover the whole output; whatever sticks out is cut off.
                if (inAspect > outAspect) sx = inAspect / outAspect else sy = outAspect / inAspect
            } else {
                // Fit inside the output with black bars.
                if (inAspect > outAspect) sy = outAspect / inAspect else sx = inAspect / outAspect
            }

            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uScale"), sx, sy)
            val pos = GLES20.glGetAttribLocation(program, "aPosition")
            val tc = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glEnableVertexAttribArray(tc)
            GLES20.glVertexAttribPointer(tc, 2, GLES20.GL_FLOAT, false, 0, TEX)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(tc)

            EGLExt.eglPresentationTimeANDROID(display, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(display, eglSurface)
            frameLock.lock()
            try {
                framesDrawn++
                frameDrawn.signalAll()
            } finally {
                frameLock.unlock()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "draw failed", t)
        }
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader error: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Link error: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val TAG = "GlScaler"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            uniform vec2 uScale;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);
                vTex = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

        private val QUAD = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEX = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    }
}
