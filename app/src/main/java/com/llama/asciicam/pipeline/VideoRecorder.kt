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
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the live ASCII output to an audio-less MP4. There's no camera/
 * screen frame to just forward — the "video" is synthesized frame by frame.
 * Each frame is first rendered into a plain [Bitmap] via [Export.drawFrameInto]
 * — the exact same call PNG export makes — and only that finished bitmap is
 * blitted onto the [MediaCodec] encoder's input Surface (via
 * [Surface.lockCanvas]/[Surface.unlockCanvasAndPost]). Drawing text directly
 * on the encoder surface's own locked Canvas was tried first, but on-device
 * that canvas didn't reliably honor the embedded custom typeface — it fell
 * back toward a generic font, unlike a normal Bitmap-backed Canvas. Routing
 * through a Bitmap first guarantees a recorded frame looks exactly like a PNG
 * snapshot of the same moment, since only a trivial drawBitmap (no text)
 * touches the encoder surface's canvas.
 *
 * A dedicated thread resamples whatever [provideFrame] currently returns at a
 * fixed [fps] and feeds it in — there's no separate rendering pass, it's the
 * same data driving the live viewfinder.
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
    // inconsistently across vendors) — e.g. the previously-hardcoded 1080 is
    // NOT 16-aligned (1080/16 = 67.5) while 1440 happens to be, which line up
    // with exactly the kind of severe text-shape distortion reported here.
    // Align both dimensions up-front so every buffer (Bitmap, MediaFormat,
    // Surface) agrees on the same, safe size.
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
    // Bitmap canvas; the encoder surface only ever receives a plain blit.
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

    /** Starts encoding on a dedicated background thread. Must be called off the
     * main thread (does content-resolver / MediaCodec setup). Returns false if
     * setup failed (nothing was started; safe to retry). */
    fun start(): Boolean {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
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
                    val surfaceCanvas = surface.lockCanvas(null)
                    try {
                        surfaceCanvas.drawBitmap(frameBitmap, 0f, 0f, null)
                    } finally {
                        surface.unlockCanvasAndPost(surfaceCanvas)
                    }
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
        finish()
    }

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
    }
}
