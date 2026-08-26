package com.llama.asciicam.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.llama.asciicam.R

/**
 * Android-graphics-backed glyph measurement: character aspect ratio, vertical
 * baseline centering offset, and per-character "ink density" used to build a
 * brightness ramp — the on-device equivalents of the original web tool's
 * `measureCharAspect()`, `measureBaselineOffsetRatio()`, and the ramp-building
 * step that rasterizes each character to measure its average darkness/coverage.
 *
 * Kept separate from [AsciiPipeline] so that module stays pure/testable; this
 * file is the only place doing Bitmap/Canvas/Paint work for glyph metrics.
 */
object GlyphMetrics {

    private const val PROBE_SIZE = 64f
    private const val DENSITY_BITMAP_SIZE = 32

    // Cached only on success — a transient load failure must NOT get stuck
    // here, since that would silently poison every future caller (including
    // ones on a different thread, like VideoRecorder's) into the fallback
    // font forever for the rest of the process. @Volatile so a load that
    // completes on one thread is visible to callers on another right away.
    @Volatile private var cachedModernDos: Typeface? = null

    fun typefaceFor(context: Context, font: FontChoice): Typeface = when (font) {
        FontChoice.MODERN_DOS -> cachedModernDos ?: loadModernDos(context).also { cachedModernDos = it } ?: Typeface.MONOSPACE
        FontChoice.MONOSPACE -> Typeface.MONOSPACE
        FontChoice.SERIF_MONO -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    /**
     * Like [typefaceFor], but never returns the shared cached instance for
     * MODERN_DOS — always loads a fresh, independent [Typeface] object.
     *
     * Callers MUST call this from the main thread. The live viewfinder and
     * PNG export have only ever loaded fonts from the main thread and have
     * always rendered the correct font; a recording, uniquely, used to load
     * its own copy from [com.llama.asciicam.pipeline.VideoRecorder]'s
     * dedicated background thread instead — and recorded video, uniquely,
     * has shown an obviously wrong, generic-looking font (confirmed by
     * screenshot: different glyph outlines entirely, not blur or a scaling
     * artifact — e.g. '%' and '*' rendering as a plain sans-serif font's
     * shapes instead of "Modern DOS 8x8"'s blocky pixel-art ones). Loading
     * fonts off the main thread is a documented source of exactly this
     * failure mode for [androidx.core.content.res.ResourcesCompat.getFont]
     * (see [loadModernDos]'s comment on why this uses the plain platform API
     * instead) — this was never actually confirmed to be safe for
     * `Resources.getFont()` either, it was just assumed switching APIs would
     * be enough. [com.llama.asciicam.ui.AsciiViewModel.startRecording] now
     * calls this synchronously, before launching the background coroutine
     * that constructs the recorder, specifically so the load happens on the
     * same thread as every font load that's ever come out correct.
     *
     * Kept as a fresh instance (rather than switching to the shared
     * [typefaceFor] cache now that the thread is fixed) so the recorder still
     * never shares a native Typeface handle with whatever the live view is
     * concurrently drawing with during the recording itself — changing one
     * variable (which thread *loads* it) at a time.
     */
    fun independentTypefaceFor(context: Context, font: FontChoice): Typeface = when (font) {
        FontChoice.MODERN_DOS -> loadModernDos(context) ?: Typeface.MONOSPACE
        FontChoice.MONOSPACE -> Typeface.MONOSPACE
        FontChoice.SERIF_MONO -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    // Resources.getFont() is the plain platform API (available since API 26,
    // this app's minSdk) for loading a font resource — used directly instead
    // of AndroidX's ResourcesCompat.getFont(), which layers on a
    // compatibility shim (with its own async-callback machinery and internal
    // cache) that this minSdk doesn't need and that has had known edge cases
    // returning null/a substitute font on some devices when called off the
    // main thread. One retry on failure: callers are now expected to call
    // this from the main thread (see independentTypefaceFor's doc), which
    // rules out the main suspected cause of a transient failure here, but a
    // single retry is nearly free insurance against any other one.
    private fun loadModernDos(context: Context): Typeface? {
        repeat(2) { attempt ->
            try {
                return context.resources.getFont(R.font.modern_dos_8x8)
            } catch (e: Exception) {
                Log.e("GlyphMetrics", "Failed to load modern_dos_8x8 font (attempt ${attempt + 1})", e)
            }
        }
        return null
    }

    /** width/height ratio of a representative glyph, used to derive cell width from font size. */
    fun measureCharAspect(typeface: Typeface): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = PROBE_SIZE
        }
        val width = paint.measureText("#")
        return (width / PROBE_SIZE).coerceIn(0.2f, 1.2f)
    }

    /**
     * Ratio (relative to text size) to shift a `drawText` baseline so that the
     * glyph is vertically centered in its cell — mirrors Canvas2D's
     * `textBaseline = "middle"`, which Android's Paint/drawText has no direct
     * equivalent for.
     */
    fun measureBaselineOffsetRatio(typeface: Typeface): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = PROBE_SIZE
        }
        val fm = paint.fontMetrics
        return (fm.ascent + fm.descent) / 2f / PROBE_SIZE
    }

    /**
     * Renders each distinct character in [ramp] into a small probe bitmap and
     * measures its average red-channel coverage (ink density), then returns the
     * characters re-ordered ascending by that density (darkest/emptiest first).
     * Matches the original tool's dynamic ramp-building so an arbitrary
     * user-supplied ramp string still produces a monotonic brightness mapping.
     */
    fun buildDensitySortedRamp(ramp: String, typeface: Typeface, bold: Boolean = true): String {
        if (ramp.isEmpty()) return " "
        val distinct = LinkedHashSet<Char>().apply { ramp.forEach { add(it) } }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = if (bold) Typeface.create(typeface, Typeface.BOLD) else typeface
            textSize = DENSITY_BITMAP_SIZE * 0.9f
            color = Color.RED
            textAlign = Paint.Align.CENTER
        }
        val bmp = Bitmap.createBitmap(DENSITY_BITMAP_SIZE, DENSITY_BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fm = paint.fontMetrics
        val baselineY = DENSITY_BITMAP_SIZE / 2f - (fm.ascent + fm.descent) / 2f

        val densities = distinct.associateWith { ch ->
            bmp.eraseColor(Color.BLACK)
            canvas.drawText(ch.toString(), DENSITY_BITMAP_SIZE / 2f, baselineY, paint)
            var sum = 0L
            val pixels = IntArray(DENSITY_BITMAP_SIZE * DENSITY_BITMAP_SIZE)
            bmp.getPixels(pixels, 0, DENSITY_BITMAP_SIZE, 0, 0, DENSITY_BITMAP_SIZE, DENSITY_BITMAP_SIZE)
            for (p in pixels) sum += Color.red(p)
            sum.toDouble() / pixels.size / 255.0
        }
        bmp.recycle()
        return distinct.sortedBy { densities[it] ?: 0.0 }.joinToString("")
    }
}
