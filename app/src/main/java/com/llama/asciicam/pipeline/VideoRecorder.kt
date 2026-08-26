package com.llama.asciicam.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
 * That rewrite alone didn't fix it either, which pointed further upstream —
 * at the actual [Typeface] object the text gets drawn with. [typeface] is a
 * constructor parameter, and the caller is expected to pass the *same shared
 * cached instance* the live viewfinder and PNG export draw with (see
 * [GlyphMetrics.typefaceFor]).
 *
 * This class briefly loaded its own second, independent copy instead, on a
 * never-confirmed theory that sharing a Typeface across threads was unsafe.
 * It isn't: Typeface is immutable and safe to draw with concurrently — Paint
 * is the object that can't be shared, and this class builds its own. The
 * correlation pointed the other way anyway: the recorder was the only caller
 * in the app loading its own copy, and recorded video was the only output
 * that ever came out in the wrong font — specifically Roboto/Droid Sans
 * Mono's glyph shapes (slashed zero, 6-point asterisk), i.e.
 * [Typeface.MONOSPACE], which is exactly the fallback returned when the font
 * resource fails to load. So that second load was failing while the cached
 * one had already succeeded.
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
    private val typeface: Typeface,
    private val backgroundArgb: Int,
    requestedWidth: Int,
    requestedHeight: Int,
    private val provideFrame: () -> Pair<AsciiFrameResult, GridGeometry>?,
) {
    // Back to 24 from a brief 15: the drop was meant to buy the encoder
    // headroom for an overload that turned out to be a resolution problem
    // (below), and it visibly cost framerate for nothing.
    private val fps = 24

    // The encoder's frame size, chosen by asking the device's actual H.264
    // encoder what it supports (see [chooseEncoderSize]) rather than by
    // picking a number.
    //
    // Both previous approaches were wrong in opposite directions, and the
    // recorded file's dimensions proved it:
    //
    //  - A hard 720px cap produced a 336x720 file. Spread ~100 ASCII columns
    //    across 336px and each character cell is ~3px wide. "Modern DOS 8x8"
    //    is a pixel font that only reads as blocky at its native size;
    //    squeezed into 3px cells and then upscaled back to fullscreen by the
    //    player, its glyphs turn into smooth rounded blobs — which is exactly
    //    the "clearly different font" that's been reported all along. The
    //    same minification averages thin bright glyphs into the black
    //    background, which is the "desaturated colors" report. Neither was a
    //    codec bug; both were this cap.
    //
    //  - Uncapped native size (1080x2280 on a tall phone) is ~9,700
    //    macroblocks, over H.264 Level 4.0's 8,192 limit. Configuring an
    //    encoder past its supported level is undefined territory — silent
    //    quality collapse or dropped frames, no exception thrown.
    //
    // So: prefer the exact native size (no resampling at all, the only way a
    // pixel font survives intact), and shrink only as far as the encoder's
    // reported VideoCapabilities actually require.
    private val nativeWidth = requestedWidth.coerceAtLeast(2)
    private val nativeHeight = requestedHeight.coerceAtLeast(2)

    // Public so the caller can surface them (see AsciiViewModel's recording
    // diagnostics) — the recorded file's real pixel dimensions have already
    // proved to be the fastest way to tell what the recorder actually did.
    private val chosenSize = chooseEncoderSize(nativeWidth, nativeHeight)
    val outWidth = chosenSize.width
    val outHeight = chosenSize.height

    /** Short human-readable note on how [outWidth]x[outHeight] was arrived at
     * ("native" when no resampling happens at all). Surfaced in the recording
     * toast — whether the frame is being scaled is the single most diagnostic
     * fact about how the output will look. */
    val sizeNote: String get() = chosenSize.note

    // typeface is a constructor param — the caller passes in the same shared
    // instance the live view draws with; see the class doc comment.
    private val baselineRatio = GlyphMetrics.measureBaselineOffsetRatio(typeface)

    // Built with `also { p -> ... }`, deliberately NOT `apply { ... }`.
    //
    // This was the whole bug, and it silently defeated nine attempted fixes.
    // As `Paint(...).apply { this.typeface = typeface }`, the bare `typeface`
    // on the right-hand side does not resolve to this class's constructor
    // property at all: inside `apply`, the implicit receiver is the Paint, and
    // an implicit receiver's members shadow the enclosing *class's* properties.
    // So it read Paint.getTypeface() — null on a fresh Paint — and assigned it
    // straight back. Every glyph was then drawn with Android's default
    // typeface, which is why recordings came out in a completely different
    // font no matter which Typeface was passed in, which thread loaded it, or
    // how it reached the encoder.
    //
    // The same `apply { this.typeface = typeface }` spelling appears in
    // GlyphMetrics, AsciiCanvas and Export and is correct there, because in
    // those the name refers to a local variable or function parameter — those
    // DO take precedence over an implicit receiver's members. Only here was it
    // a class property, and only recordings were ever wrong.
    //
    // `also` gives the lambda a named parameter and no implicit receiver, so
    // `typeface` unambiguously means this class's property and the shadowing
    // cannot come back.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.typeface = typeface
        p.textAlign = Paint.Align.CENTER
    }

    /**
     * Identity of the [Typeface] this recorder will actually draw every glyph
     * with, read back off the constructed [Paint] rather than from the
     * constructor argument — so it reports what will really be used, not what
     * was intended. Compared against the live view's in the recording
     * diagnostic: if a recording ever comes out in the wrong font again, this
     * says immediately whether the wrong font was already selected here or
     * whether something downstream of drawing is at fault.
     */
    val paintTypefaceIdentity: Int get() = System.identityHashCode(paint.typeface)

    // Reused across frames — one frame's worth of scratch memory, not
    // reallocated every ~40ms. Text is drawn into this (proven-correct),
    // native-sized Bitmap canvas; only its finished pixels ever reach the
    // encoder, downsized on the GPU as described above.
    private val frameBitmap = Bitmap.createBitmap(nativeWidth, nativeHeight, Bitmap.Config.ARGB_8888)
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
            // The previous attempt forced every frame to be a keyframe
            // (I-frame-interval 0), on the theory that ASCII text changes
            // character-by-character rather than "moving", so inter-frame
            // motion search would find poor matches. True, but that misses
            // that a normal P-frame encode doesn't depend on motion search
            // helping — any macroblock that doesn't match well is simply
            // intra-coded within the P-frame, same as all-intra would do for
            // it. All-intra only *loses* the ability to cheaply skip/reuse
            // the large unchanged regions (background, margins) between
            // frames, raising the bit cost for the same quality. And at
            // 20Mbps + uncapped native resolution it made things measurably
            // worse (choppy framerate, washed-out colors — classic real-
            // time-encoder-overload symptoms) while the wrong-font
            // appearance persisted unchanged — which also rules out inter-
            // frame prediction as that bug's cause, since making every frame
            // fully independent didn't change it. No known upside left to
            // pay all-intra's cost, so back to a normal 1s GOP.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // CBR instead of the (likely) default VBR. This content is
            // mostly one flat background color with sparse, thin, high-
            // contrast glyphs on top — exactly the "low complexity" frame a
            // VBR rate controller tends to under-spend bits on, which shows
            // up as heavy quantization / washed-out color on the glyphs
            // themselves even when the target bitrate looks generous on
            // average. CBR forces it to actually spend close to the full
            // target every frame.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // Scaled to the frame size actually chosen above and clamped to
            // what this encoder reports it accepts, rather than a fixed number
            // picked for one assumed resolution.
            setInteger(MediaFormat.KEY_BIT_RATE, chooseBitRate(outWidth, outHeight, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        }
        Log.i(TAG, "encoder format: ${outWidth}x$outHeight (${chosenSize.note}) from native ${nativeWidth}x$nativeHeight @${fps}fps")
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
                    Export.drawFrameInto(frameBitmapCanvas, frame, geometry, paint, baselineRatio, nativeWidth, nativeHeight, backgroundArgb)
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

        /** An encoder-accepted frame size, plus the caps it was derived from. */
        data class EncoderSize(val width: Int, val height: Int, val note: String)

        /**
         * Largest encoder-supported frame size no bigger than
         * [nativeW]x[nativeH], keeping the aspect ratio.
         *
         * Asking [MediaCodecInfo.VideoCapabilities] beats hardcoding a limit:
         * the actual constraint is per-device (supported width/height ranges,
         * required alignment, and the total-macroblock ceiling implied by the
         * encoder's H.264 level), and phone ASCII frames are an unusually tall
         * aspect ratio that generic "cap the long edge at N" rules handle
         * badly — that's how this ended up at 336x720, far below what the
         * encoder could actually have taken.
         *
         * Native size is tried first and used whenever it's supported, since
         * any resampling at all visibly degrades a pixel font.
         */
        private fun chooseEncoderSize(nativeW: Int, nativeH: Int): EncoderSize {
            val caps = findAvcEncoderCaps()
                ?: return EncoderSize(align(nativeW, 16), align(nativeH, 16), "no caps")

            val wAlign = caps.widthAlignment.coerceAtLeast(2)
            val hAlign = caps.heightAlignment.coerceAtLeast(2)

            // Walk down from 1.0; the first supported size wins. The step is
            // small so a device that only needs a slight reduction gets one.
            var scale = 1.0f
            while (scale > 0.2f) {
                val w = align((nativeW * scale).toInt(), wAlign).coerceAtLeast(wAlign)
                val h = align((nativeH * scale).toInt(), hAlign).coerceAtLeast(hAlign)
                val supported = try {
                    caps.isSizeSupported(w, h)
                } catch (e: Exception) {
                    false
                }
                if (supported) {
                    val note = if (scale >= 0.999f) "native" else "scaled ${"%.2f".format(scale)}"
                    return EncoderSize(w, h, note)
                }
                scale -= 0.05f
            }
            // Nothing matched (very unusual) — fall back to the encoder's own
            // reported upper bound rather than something invented.
            val w = align(caps.supportedWidths.upper.coerceAtMost(nativeW), wAlign)
            val h = align(
                caps.getSupportedHeightsFor(w).upper.coerceAtMost(nativeH),
                hAlign,
            )
            return EncoderSize(w.coerceAtLeast(wAlign), h.coerceAtLeast(hAlign), "caps upper")
        }

        /** Bitrate the encoder will actually accept, targeting [bitsPerPixel]. */
        private fun chooseBitRate(width: Int, height: Int, fps: Int): Int {
            // ~0.2 bits/pixel. Text on flat backgrounds is cheap for H.264 in
            // the large empty regions but expensive on the sharp glyph edges
            // that matter most here, so this sits above typical camera-video
            // rates without being the 20Mbps that previously overloaded things.
            val target = (width.toLong() * height * fps * 0.2).toLong()
                .coerceIn(2_000_000L, 24_000_000L)
                .toInt()
            val caps = findAvcEncoderCaps() ?: return target
            return try {
                caps.bitrateRange.clamp(target)
            } catch (e: Exception) {
                target
            }
        }

        private fun findAvcEncoderCaps(): MediaCodecInfo.VideoCapabilities? {
            return try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .asSequence()
                    .filter { it.isEncoder }
                    .filter { info ->
                        info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
                    }
                    .mapNotNull { info ->
                        try {
                            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query encoder capabilities", e)
                null
            }
        }

        private fun align(v: Int, to: Int): Int = ((v + to - 1) / to) * to

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
