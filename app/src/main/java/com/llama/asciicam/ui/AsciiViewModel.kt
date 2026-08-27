package com.llama.asciicam.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llama.asciicam.pipeline.AsciiFrameResult
import com.llama.asciicam.pipeline.AsciiPipeline
import com.llama.asciicam.pipeline.AsciiSettings
import com.llama.asciicam.pipeline.CharSource
import com.llama.asciicam.pipeline.Export
import com.llama.asciicam.pipeline.GlyphMetrics
import com.llama.asciicam.pipeline.GridGeometry
import com.llama.asciicam.pipeline.GridSources
import com.llama.asciicam.pipeline.MediaSource
import com.llama.asciicam.pipeline.NoiseType
import com.llama.asciicam.pipeline.PipelineState
import com.llama.asciicam.pipeline.VideoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A frame and the grid geometry it was rendered at — always published together. */
data class RenderState(val frame: AsciiFrameResult, val geometry: GridGeometry)

/**
 * Owns [AsciiSettings], the persistent [PipelineState], and the latest
 * rendered [AsciiFrameResult] the UI draws. Camera frames call [onCameraFrame]
 * directly from the analyzer's background executor; the noise source drives
 * itself with a coroutine loop; the image source re-renders whenever a
 * relevant setting changes.
 */
class AsciiViewModel(app: Application) : AndroidViewModel(app) {

    var settings by mutableStateOf(
        AsciiSettings(cols = AsciiSettings.CAMERA_DEFAULT_COLS)
    )
        private set

    /** Frame + geometry published together as one snapshot write, so a reader
     * that takes a single [render] snapshot never sees a mismatched pair. */
    var render by mutableStateOf<RenderState?>(null)
        private set

    var pickedImage by mutableStateOf<Bitmap?>(null)
        private set

    private val pipelineState = PipelineState()
    private val processMutex = Mutex()

    private var cachedRampKey: String? = null
    private var cachedSortedRamp: String = ""
    private var cachedAspectFont = settings.font
    private var cachedCharAspect = GlyphMetrics.measureCharAspect(GlyphMetrics.typefaceFor(getApplication<Application>(), settings.font))
    private var cachedFontSizeScale = GlyphMetrics.fontSizeScaleFor(getApplication<Application>(), settings.font)

    private var noiseJob: Job? = null
    private var noiseClock = 0f
    private var lastFrameNanos = 0L

    // Last known camera source (post-rotation) dimensions, used by the analyzer's
    // rows() callback to size the next requested grid before that frame arrives.
    // Defaults to a plausible 4:3 guess until the first real frame is seen.
    @Volatile private var lastSrcW = 4
    @Volatile private var lastSrcH = 3

    // Live viewfinder size in px, reported by AsciiCanvas via reportViewportSize().
    // Font size isn't a separate setting any more — computeGridGeometry solves
    // for it from this width so the grid always fills the screen for whatever
    // `cols` is set to, and the noise source uses it as its source aspect
    // ratio too (it has no real footage to take one from otherwise). Defaults
    // to a plausible full-HD-portrait guess until the first real layout pass.
    @Volatile private var viewportW = 1080
    @Volatile private var viewportH = 1920

    /**
     * Camera zoom, as a CameraX zoom *ratio* (1.0 = no zoom). Applied by
     * [CameraHost] to the bound camera's control, so it changes the frames the
     * sensor hands to the analyzer and nothing else — the ASCII grid, its
     * geometry and every pipeline setting are untouched, exactly as if the
     * phone had been moved closer.
     *
     * [zoomMin]/[zoomMax] are reported by [CameraHost] from the bound camera,
     * since the usable range is per-device and differs between the front and
     * back cameras.
     */
    var zoomRatio by mutableStateOf(1f)
        private set
    private var zoomMin = 1f
    private var zoomMax = 1f

    /** Called by [CameraHost] once a camera is bound and its range is known. */
    fun reportZoomRange(min: Float, max: Float) {
        if (min <= 0f || max <= 0f || max < min) return
        zoomMin = min
        zoomMax = max
        // A camera swap can narrow the range out from under the current value
        // (front cameras often top out far lower than the back).
        val clamped = zoomRatio.coerceIn(min, max)
        if (clamped != zoomRatio) zoomRatio = clamped
    }

    /** Multiplies the current zoom by a pinch gesture's scale factor. */
    fun onPinchZoom(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        val next = (zoomRatio * scaleFactor).coerceIn(zoomMin, zoomMax)
        if (next != zoomRatio) zoomRatio = next
    }

    fun reportViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (viewportW == widthPx && viewportH == heightPx) return
        viewportW = widthPx
        viewportH = heightPx
    }

    /** height/width ratio of the live viewfinder — CameraFrameAnalyzer center-crops
     * the sensor frame to this so the camera grid fills the whole screen. */
    fun currentViewportAspect(): Float = viewportH.toFloat() / viewportW.coerceAtLeast(1)

    fun updateSettings(transform: (AsciiSettings) -> AsciiSettings) {
        val old = settings
        val new = transform(old)
        settings = new
        // Grid-affecting fields (cols/fontSize/spacing/font) don't need special
        // handling here — PipelineState.ensureSize() detects the resize itself
        // the next time a frame is processed.
        if (old.mediaSource != new.mediaSource) {
            pipelineState.reset()
            manageNoiseLoop()
        }
        if (new.mediaSource == MediaSource.NOISE && noiseJob == null) manageNoiseLoop()
        if (new.mediaSource == MediaSource.IMAGE && pickedImage != null &&
            (old.mediaSource != new.mediaSource || settingsAffectImageRerender(old, new))
        ) {
            renderPickedImage()
        }
    }

    private fun settingsAffectImageRerender(a: AsciiSettings, b: AsciiSettings): Boolean = a != b

    fun onImagePicked(bitmap: Bitmap?) {
        pickedImage = bitmap
        if (bitmap != null) {
            updateSettings { it.copy(mediaSource = MediaSource.IMAGE) }
            renderPickedImage()
        }
    }

    private fun ensureRamp(): String {
        val key = settings.rampString + "|" + settings.font.name
        if (key != cachedRampKey) {
            cachedSortedRamp = GlyphMetrics.buildDensitySortedRamp(settings.rampString, GlyphMetrics.typefaceFor(getApplication<Application>(), settings.font))
            cachedRampKey = key
        }
        return cachedSortedRamp
    }

    private fun ensureCharAspect(): Float {
        refreshFontMetricsIfNeeded()
        return cachedCharAspect
    }

    /** Per-font glyph shrink factor so a newly-chosen font still fits its
     * cells — see [GlyphMetrics.fontSizeScaleFor]. */
    private fun ensureFontSizeScale(): Float {
        refreshFontMetricsIfNeeded()
        return cachedFontSizeScale
    }

    private fun refreshFontMetricsIfNeeded() {
        if (cachedAspectFont == settings.font) return
        val app = getApplication<Application>()
        cachedCharAspect = GlyphMetrics.measureCharAspect(GlyphMetrics.typefaceFor(app, settings.font))
        cachedFontSizeScale = GlyphMetrics.fontSizeScaleFor(app, settings.font)
        cachedAspectFont = settings.font
    }

    /** Called from the CameraX analyzer's background thread with a freshly downsampled grid. */
    fun onCameraFrame(r: FloatArray, g: FloatArray, b: FloatArray, cols: Int, rows: Int, srcW: Int, srcH: Int) {
        if (settings.mediaSource != MediaSource.CAMERA) return
        if (!processMutex.tryLock()) return // drop frame if still busy — keeps up with live camera
        try {
            processAndPublish(r, g, b, cols, rows, srcW, srcH, temporal = true)
        } finally {
            processMutex.unlock()
        }
    }

    private fun processAndPublish(
        r: FloatArray, g: FloatArray, b: FloatArray,
        cols: Int, rows: Int, srcW: Int, srcH: Int,
        temporal: Boolean,
    ) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 30f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now

        val s = settings
        val charAspect = ensureCharAspect()
        val geom = AsciiPipeline.computeGridGeometry(s, srcW, srcH, charAspect, viewportW.toFloat(), ensureFontSizeScale())
        // The caller already downsampled to (cols, rows) from the *previous* geometry
        // request; if geometry's row count differs (e.g. right after a cols change),
        // this frame's row count won't line up. Re-derive using the actually-supplied
        // grid dims to stay consistent, and let the next frame pick up new geometry.
        val effectiveGeom = if (geom.cols == cols && geom.rows == rows) geom else geom.copy(cols = cols, rows = rows)

        val ramp = if (s.charSource == CharSource.RAMP) ensureRamp() else ""
        val result = AsciiPipeline.process(
            rawR = r, rawG = g, rawB = b,
            cols = cols, rows = rows,
            settings = s,
            state = pipelineState,
            dtSeconds = dt,
            applyTemporalSmoothing = temporal,
            sortedRamp = ramp,
        )
        render = RenderState(result, effectiveGeom)
        lastSrcW = srcW
        lastSrcH = srcH
    }

    /** Grid size [CameraFrameAnalyzer] should target for its next frame (called off the main thread). */
    fun currentGridCols(): Int = settings.cols.coerceIn(1, AsciiPipeline.MAX_COLS)
    fun currentGridRows(): Int {
        val charAspect = ensureCharAspect()
        return AsciiPipeline.computeGridGeometry(settings, lastSrcW, lastSrcH, charAspect, viewportW.toFloat(), ensureFontSizeScale()).rows
    }

    private fun manageNoiseLoop() {
        noiseJob?.cancel()
        noiseJob = null
        if (settings.mediaSource != MediaSource.NOISE) return
        noiseJob = viewModelScope.launch(Dispatchers.Default) {
            var lastNanos = System.nanoTime()
            while (true) {
                val s = settings
                val now = System.nanoTime()
                val dt = (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                if (!s.noiseFrozen) noiseClock += dt.coerceIn(0f, 0.1f) * s.noiseSpeed

                val charAspect = ensureCharAspect()
                val srcW = viewportW
                val srcH = viewportH
                val geom = AsciiPipeline.computeGridGeometry(s, srcW, srcH, charAspect, viewportW.toFloat(), ensureFontSizeScale())
                val n = geom.cols * geom.rows
                val rr = FloatArray(n); val gg = FloatArray(n); val bb = FloatArray(n)
                GridSources.sampleNoise(s.noiseType, geom.cols, geom.rows, noiseClock, s.noiseScale, rr, gg, bb)

                processMutex.withLock {
                    processAndPublish(rr, gg, bb, geom.cols, geom.rows, srcW, srcH, temporal = true)
                }
                kotlinx.coroutines.delay(33)
            }
        }
    }

    private fun renderPickedImage() {
        val bmp = pickedImage ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val s = settings
            val charAspect = ensureCharAspect()
            val geom = AsciiPipeline.computeGridGeometry(s, bmp.width, bmp.height, charAspect, viewportW.toFloat(), ensureFontSizeScale())
            val n = geom.cols * geom.rows
            val rr = FloatArray(n); val gg = FloatArray(n); val bb = FloatArray(n)
            GridSources.sampleBitmap(bmp, geom.cols, geom.rows, rr, gg, bb)
            processMutex.withLock {
                processAndPublish(rr, gg, bb, geom.cols, geom.rows, bmp.width, bmp.height, temporal = false)
            }
        }
    }

    fun exportPng(context: android.content.Context, onDone: (Boolean) -> Unit) {
        val snapshot = render
        if (snapshot == null) { onDone(false); return }
        viewModelScope.launch(Dispatchers.Default) {
            val bgArgb = if (settings.invert) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            // Use the geometry's own native content size — matching it exactly
            // means Export.drawFrameInto's internal fit scale stays ~1.0 and
            // there's no letterboxing. A previous hardcoded 1080x1440 rarely
            // matched a phone's actual (much taller) aspect ratio.
            val widthPx = (snapshot.geometry.cols * snapshot.geometry.cellW).toInt().coerceAtLeast(2)
            val heightPx = (snapshot.geometry.rows * snapshot.geometry.rowPitch).toInt().coerceAtLeast(2)
            val bmp = Export.renderToBitmap(
                context, snapshot.frame, snapshot.geometry, settings.font, bgArgb, widthPx, heightPx,
            )
            val ok = Export.savePng(context, bmp)
            bmp.recycle()
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun exportTxt(context: android.content.Context, onDone: (Boolean) -> Unit) {
        val snapshot = render
        if (snapshot == null) { onDone(false); return }
        viewModelScope.launch(Dispatchers.Default) {
            val ok = Export.saveTxt(context, snapshot.frame)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    var isRecording by mutableStateOf(false)
        private set

    private var videoRecorder: VideoRecorder? = null

    /** Starts recording the live ASCII output to Movies/AsciiCam as an MP4.
     * A no-op if already recording. [onStarted] reports whether setup
     * (encoder/MediaStore) actually succeeded, plus a short human-readable
     * diagnostic string for the caller to surface (see [RECORDING_BUILD_MARKER]). */
    fun startRecording(context: android.content.Context, onStarted: (Boolean, String) -> Unit) {
        if (isRecording || videoRecorder != null) { onStarted(false, "already recording"); return }
        // The *same shared cached* Typeface instance the live viewfinder and
        // PNG export draw with — not a second, independently-loaded copy.
        //
        // The recorder used to load its own via a dedicated
        // GlyphMetrics.independentTypefaceFor(); it was the only caller in the
        // app that did, and recorded video was the only output that ever came
        // out in the wrong font. The glyph shapes in the recording (slashed
        // zero, 6-point asterisk) identify it as Typeface.MONOSPACE — the
        // fallback returned when the font resource fails to load — so that
        // second load was failing while the first, cached one had succeeded.
        // Sharing one instance is also simply correct: Typeface is immutable
        // and safe to draw with from multiple threads (Paint isn't, and each
        // path already builds its own).
        val typeface = GlyphMetrics.typefaceFor(context, settings.font)
        val usingFallbackFont = settings.font == com.llama.asciicam.pipeline.FontChoice.MODERN_DOS &&
            typeface === android.graphics.Typeface.MONOSPACE
        viewModelScope.launch(Dispatchers.Default) {
            val bgArgb = if (settings.invert) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            // Use the live geometry's actual native content size (cols*cellW x
            // rows*rowPitch), not an independently-derived viewport estimate —
            // matching it exactly means Export.drawFrameInto's internal fit
            // scale stays ~1.0. A prior version capped a viewport-based guess
            // to a 1080 max dimension, which for most phones came out smaller
            // than the live content size (confirmed via logcat: target=498x1080
            // against a ~1080px-wide live view) — so every frame was rendered
            // at its normal (larger) font size and then shrunk via
            // canvas.scale(). "Modern DOS 8x8" is a pixel/bitmap-style font
            // that only looks crisp at the sizes it was designed for; shrinking
            // an already-hinted/rasterized glyph like that is exactly the kind
            // of thing that can make it look like a completely different,
            // smoother font once re-rasterized at the scaled-down size — which
            // matches the screenshot (clean curved glyphs, not the blocky
            // pixel-art look) far better than a codec/encoding explanation.
            // VideoRecorder still caps what it hands the *encoder* to keep
            // real-time H.264 encoding sustainable, but it does that itself
            // downstream of drawing (GPU-downsampling the finished, natively-
            // rendered frame — see its nativeWidth/outWidth split) instead of
            // here, so glyph rasterization itself never sees a size mismatch.
            val snapshotGeometry = render?.geometry
            val targetW: Int
            val targetH: Int
            if (snapshotGeometry != null) {
                targetW = (snapshotGeometry.cols * snapshotGeometry.cellW).toInt().coerceAtLeast(2)
                targetH = (snapshotGeometry.rows * snapshotGeometry.rowPitch).toInt().coerceAtLeast(2)
            } else {
                // No frame yet (recording tapped before the first frame arrived) —
                // fall back to the raw viewport size, still native/unscaled.
                targetW = viewportW.coerceAtLeast(2)
                targetH = viewportH.coerceAtLeast(2)
            }
            val recorder = VideoRecorder(
                context = context,
                typeface = typeface,
                backgroundArgb = bgArgb,
                requestedWidth = targetW,
                requestedHeight = targetH,
                provideFrame = { render?.let { it.frame to it.geometry } },
            )
            val ok = recorder.start()
            // Surfaced on-screen (a Toast — Logcat needs adb, which isn't set
            // up on the test machine) because after this many failed fixes the
            // thing most worth establishing is no longer "which fix works" but
            // "is the phone even running the code I pushed". Both of these are
            // decisive: the build marker is bumped every push, and the encoder
            // size is capped by this build (MAX_ENCODER_DIM) but uncapped in
            // every older one, so a full-resolution number here means a stale
            // APK. The font flag reports whether the Modern DOS typeface
            // actually loaded or silently fell back to MONOSPACE.
            // "tf" pairs the recorder's real draw-time typeface identity with
            // the live view's. They must match now that both come from the one
            // shared cached instance; if a recording still comes out in the
            // wrong font while these agree, the typeface is finally ruled out
            // and the fault is downstream of drawing.
            val liveTypefaceIdentity = System.identityHashCode(typeface)
            val tfNote = if (recorder.paintTypefaceIdentity == liveTypefaceIdentity) {
                "tf=match"
            } else {
                "tf=MISMATCH(${recorder.paintTypefaceIdentity}≠$liveTypefaceIdentity)"
            }
            val diagnostic = "$RECORDING_BUILD_MARKER · ${recorder.outWidth}x${recorder.outHeight} " +
                "(${recorder.sizeNote}) · " + (if (usingFallbackFont) "FONT=FALLBACK" else "font ok") +
                " · $tfNote"
            android.util.Log.i("AsciiViewModel", "startRecording: ok=$ok $diagnostic")
            withContext(Dispatchers.Main) {
                if (ok) {
                    videoRecorder = recorder
                    isRecording = true
                }
                onStarted(ok, diagnostic)
            }
        }
    }

    /** Stops an in-progress recording and finalizes the file. A no-op if not
     * currently recording. [onDone]'s second argument is the frame rate the
     * recording actually achieved, for the caller to surface. */
    fun stopRecording(onDone: (Boolean, Double) -> Unit) {
        val recorder = videoRecorder
        if (recorder == null) { onDone(false, 0.0); return }
        videoRecorder = null
        isRecording = false
        viewModelScope.launch(Dispatchers.Default) {
            recorder.stop()
            // Read after stop() returns — that joins the recording thread, so
            // the measurement is complete by now.
            val fps = recorder.achievedFps
            withContext(Dispatchers.Main) { onDone(true, fps) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        videoRecorder?.let { r ->
            // Best-effort: finalize on a plain thread since viewModelScope is
            // already cancelled by the time onCleared() runs.
            Thread { r.stop() }.start()
        }
    }

    companion object {
        /**
         * Bumped on every push while the recording bug is being chased. Shown
         * in the toast when a recording starts, purely so it's unambiguous
         * on-device which build is actually installed — several rounds of
         * "nothing changed at all" are indistinguishable from a stale APK
         * otherwise, and that ambiguity has cost more than the fixes have.
         */
        const val RECORDING_BUILD_MARKER = "build-12"
    }
}
