package com.llama.asciicam.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the live ASCII output to an audio-less MP4. There's no camera/
 * screen frame to just forward — the "video" is synthesized frame by frame.
 * Each frame is first rendered into a plain [Bitmap] via [Export.drawFrameInto]
 * — the exact same call PNG export makes, proven correct by dumping it
 * directly to a PNG mid-recording — then uploaded as a GL texture and drawn
 * onto the [MediaCodec] encoder's input Surface via OpenGL ES (a full-screen
 * textured quad, [EGL14.eglSwapBuffers] submitting each frame).
 *
 * An earlier version fed frames to the encoder surface via
 * [Surface.lockCanvas]/`drawBitmap` (software rendering). That produced a
 * video where the ASCII glyphs rendered in a completely different, generic-
 * looking font — confirmed (by dumping the pre-submission Bitmap to a PNG
 * bypassing the encoder entirely) to already be *correct* at that point, so
 * the corruption was happening specifically in how that Bitmap reached the
 * encoder. `Surface.lockCanvas()` on a `MediaCodec.createInputSurface()`
 * Surface is a supported but less-common path; feeding such a surface via
 * OpenGL ES — the same technique Android's own screen-recording and camera-
 * to-encoder pipelines use — is the standard, thoroughly-tested way these
 * surfaces are meant to be driven.
 *
 * A dedicated thread resamples whatever [provideFrame] currently returns at a
 * fixed [fps] and feeds it in — there's no separate rendering pass, it's the
 * same data driving the live viewfinder. All EGL/GL setup and every GL call
 * happen on that same thread (an EGL context is only usable on the thread
 * it's current on).
 *
 * [provideFrame] deliberately doesn't reference AsciiViewModel directly, to
 * keep this class decoupled from Compose/ViewModel plumbing — the caller
 * supplies a plain lambda.
 */
class VideoRecorder(
    private val context: Context,
    font: FontChoice,
    private val backgroundArgb: Int,
    requestedWidth: Int,
    requestedHeight: Int,
    private val provideFrame: () -> Pair<AsciiFrameResult, GridGeometry>?,
) {
    private val fps = 24

    // H.264 hardware encoders operate on 16x16 macroblocks; a Surface-input
    // size that isn't a multiple of 16 on both axes is a well-known source of
    // on-device distortion (padding/scaling to the macroblock grid handled
    // inconsistently across vendors).
    private val outWidth = align16(requestedWidth)
    private val outHeight = align16(requestedHeight)

    // Independent instance, not the shared cached one — see
    // GlyphMetrics.independentTypefaceFor's doc for why.
    private val typeface = GlyphMetrics.independentTypefaceFor(context, font)
    private val baselineRatio = GlyphMetrics.measureBaselineOffsetRatio(typeface)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = Paint.Align.CENTER
    }

    // Reused across frames — one frame's worth of scratch memory, not
    // reallocated every ~40ms. Text is drawn into this (proven-correct)
    // Bitmap canvas; only its finished pixels ever reach the encoder.
    private val frameBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    private val frameBitmapCanvas = Canvas(frameBitmap)

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var thread: Thread? = null
    @Volatile private var recording = false

    // EGL/GL state — created and used exclusively on the recording thread.
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glProgram = 0
    private var glTexture = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureUniform = 0

    /** Starts encoding on a dedicated background thread. Must be called off the
     * main thread (does content-resolver / MediaCodec setup). Returns false if
     * setup failed (nothing was started; safe to retry). */
    fun start(): Boolean {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Much higher than typical camera-video bitrate, and every frame a
            // keyframe: ASCII-art text changes character-by-character between
            // frames rather than "moving" the way real video content does, so
            // inter-frame motion compensation finds poor matches for it — that
            // forces heavy quantization of the leftover prediction error on
            // every non-keyframe. With only 1 keyframe/sec at a modest 6Mbps,
            // nearly every displayed frame was a heavily-compressed P-frame.
            // Confirmed (via a pre-encode PNG dump) that the drawn bitmap itself
            // is correct and (via a full rewrite to OpenGL submission) that how
            // frames reach the encoder isn't the issue either — encoding itself
            // is the one remaining constant across every failure, and text
            // content is exactly the case general-purpose video compression
            // handles worst.
            setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
        }
        val enc = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create/configure encoder", e)
            return false
        }

        val surface = try {
            enc.createInputSurface()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encoder input surface", e)
            enc.release()
            return false
        }

        val target = openMediaStoreTarget()
        if (target == null) {
            surface.release(); enc.release()
            return false
        }
        val (u, fd) = target

        val mux = try {
            MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create muxer", e)
            surface.release(); enc.release(); fd.close()
            deleteFailedEntry(u)
            return false
        }

        try {
            enc.start()
        } catch (e: Exception) {
            Log.e(TAG, "encoder.start() failed", e)
            surface.release(); enc.release(); mux.release(); fd.close()
            deleteFailedEntry(u)
            return false
        }

        codec = enc
        inputSurface = surface
        muxer = mux
        pfd = fd
        uri = u
        muxerStarted = false
        trackIndex = -1
        recording = true

        thread = Thread(::runLoop, "AsciiCam-VideoRecorder").apply { start() }
        return true
    }

    /** Signals the recording thread to wrap up (flush, finalize the file) and
     * blocks until it has. Safe to call from a background thread only. */
    fun stop() {
        recording = false
        thread?.join(5_000)
        thread = null
    }

    private fun runLoop() {
        val enc = codec ?: return
        val surface = inputSurface ?: return

        try {
            setupEgl(surface)
            setupGl()
        } catch (e: Exception) {
            Log.e(TAG, "EGL/GL setup failed", e)
            recording = false
            releaseEgl()
            finish()
            return
        }

        val frameIntervalMs = 1000L / fps
        var nextFrameAt = System.currentTimeMillis()

        while (recording) {
            drainEncoder(enc, endOfStream = false)

            val now = System.currentTimeMillis()
            if (now >= nextFrameAt) {
                val current = provideFrame()
                if (current != null) {
                    val (frame, geometry) = current
                    Export.drawFrameInto(frameBitmapCanvas, frame, geometry, paint, baselineRatio, outWidth, outHeight, backgroundArgb)
                    drawFrameToSurfaceGl()
                }
                nextFrameAt += frameIntervalMs
                if (nextFrameAt < now) nextFrameAt = now + frameIntervalMs
            }
            Thread.sleep(4)
        }

        try {
            enc.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.e(TAG, "signalEndOfInputStream failed", e)
        }
        drainEncoder(enc, endOfStream = true)
        releaseEgl()
        finish()
    }

    // ---------- EGL / GL ----------

    private fun setupEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        val config = configs[0] ?: throw RuntimeException("eglChooseConfig returned no config")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun setupGl() {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
        glProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(glProgram, vertexShader)
        GLES20.glAttachShader(glProgram, fragmentShader)
        GLES20.glLinkProgram(glProgram)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(glProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(glProgram)
            GLES20.glDeleteProgram(glProgram)
            throw RuntimeException("Program link failed: $log")
        }

        positionHandle = GLES20.glGetAttribLocation(glProgram, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        textureUniform = GLES20.glGetUniformLocation(glProgram, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        glTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun drawFrameToSurfaceGl() {
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(glProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frameBitmap, 0)
        GLES20.glUniform1i(textureUniform, 0)

        VERTEX_DATA.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, VERTEX_DATA)

        VERTEX_DATA.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, VERTEX_DATA)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    // ---------- MediaCodec / MediaMuxer ----------

    private val bufferInfo = MediaCodec.BufferInfo()

    private fun drainEncoder(enc: MediaCodec, endOfStream: Boolean) {
        while (true) {
            val outIndex = try {
                enc.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (e: Exception) {
                Log.e(TAG, "dequeueOutputBuffer failed", e)
                return
            }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val mux = muxer ?: return
                    trackIndex = mux.addTrack(enc.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encodedData = enc.getOutputBuffer(outIndex)
                    if (encodedData != null) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun finish() {
        try {
            if (muxerStarted) muxer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "muxer.stop() failed", e)
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        try { frameBitmap.recycle() } catch (_: Exception) {}

        val u = uri
        if (u != null && muxerStarted) {
            finalizeMediaStoreEntry(u)
        } else if (u != null) {
            // Nothing was ever muxed (e.g. stopped almost instantly) — don't
            // leave a zero-byte "pending" entry behind.
            deleteFailedEntry(u)
        }

        codec = null; muxer = null; inputSurface = null; pfd = null; uri = null
    }

    private fun openMediaStoreTarget(): Pair<Uri, ParcelFileDescriptor>? {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "asciicam_$ts.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AsciiCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        return try {
            val u = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val fd = resolver.openFileDescriptor(u, "rw") ?: run {
                resolver.delete(u, null, null)
                return null
            }
            u to fd
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open MediaStore target", e)
            null
        }
    }

    private fun finalizeMediaStoreEntry(u: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            try { context.contentResolver.update(u, values, null, null) } catch (_: Exception) {}
        }
    }

    private fun deleteFailedEntry(u: Uri) {
        try { context.contentResolver.delete(u, null, null) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VideoRecorder"
        private fun align16(v: Int): Int = ((v + 15) / 16) * 16

        // Not defined in EGL14 itself; required in the config attribs so the
        // chosen config is guaranteed compatible with MediaCodec's input surface.
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_STRIDE_BYTES = 4 * 4 // 4 floats/vertex * 4 bytes/float

        private val VERTEX_SHADER_SRC = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_SRC = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        // Full-screen triangle strip: clip-space (x,y) + texture (u,v) per vertex.
        // Bitmap row 0 (its top) is uploaded to texture v=0; pairing that with
        // screen-space y=+1 (top of the viewport) keeps the image right-side up.
        private val VERTEX_DATA: FloatBuffer = ByteBuffer.allocateDirect(4 * VERTEX_STRIDE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        -1f, -1f, 0f, 1f, // bottom-left
                        1f, -1f, 1f, 1f, // bottom-right
                        -1f, 1f, 0f, 0f, // top-left
                        1f, 1f, 1f, 0f, // top-right
                    ),
                )
                position(0)
            }
    }
}
