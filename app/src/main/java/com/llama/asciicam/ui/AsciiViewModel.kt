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

    fun reportViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (viewportW == widthPx && viewportH == heightPx) return
        viewportW = widthPx
        viewportH = heightPx
    }

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
        if (cachedAspectFont != settings.font) {
            cachedCharAspect = GlyphMetrics.measureCharAspect(GlyphMetrics.typefaceFor(getApplication<Application>(), settings.font))
            cachedAspectFont = settings.font
        }
        return cachedCharAspect
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
        val geom = AsciiPipeline.computeGridGeometry(s, srcW, srcH, charAspect, viewportW.toFloat())
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
    fun currentGridCols(): Int = settings.cols.coerceAtLeast(1)
    fun currentGridRows(): Int {
        val charAspect = ensureCharAspect()
        return AsciiPipeline.computeGridGeometry(settings, lastSrcW, lastSrcH, charAspect, viewportW.toFloat()).rows
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
                val geom = AsciiPipeline.computeGridGeometry(s, srcW, srcH, charAspect, viewportW.toFloat())
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
            val geom = AsciiPipeline.computeGridGeometry(s, bmp.width, bmp.height, charAspect, viewportW.toFloat())
            val n = geom.cols * geom.rows
            val rr = FloatArray(n); val gg = FloatArray(n); val bb = FloatArray(n)
            GridSources.sampleBitmap(bmp, geom.cols, geom.rows, rr, gg, bb)
            processMutex.withLock {
                processAndPublish(rr, gg, bb, geom.cols, geom.rows, bmp.width, bmp.height, temporal = false)
            }
        }
    }

    fun exportPng(context: android.content.Context, widthPx: Int, heightPx: Int, onDone: (Boolean) -> Unit) {
        val snapshot = render
        if (snapshot == null) { onDone(false); return }
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = Export.renderToBitmap(
                context, snapshot.frame, snapshot.geometry, settings.font, android.graphics.Color.BLACK, widthPx, heightPx,
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
}
