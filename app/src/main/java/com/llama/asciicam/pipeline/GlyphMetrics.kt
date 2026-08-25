package com.llama.asciicam.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
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

    // ResourcesCompat.getFont() does its own internal caching, but this avoids
    // repeating the lookup (and its cache-key hashing) on every call.
    private var cachedModernDos: Typeface? = null

    fun typefaceFor(context: Context, font: FontChoice): Typeface = when (font) {
        FontChoice.MODERN_DOS -> cachedModernDos ?: (ResourcesCompat.getFont(context, R.font.modern_dos_8x8) ?: Typeface.MONOSPACE).also { cachedModernDos = it }
        FontChoice.MONOSPACE -> Typeface.MONOSPACE
        FontChoice.SERIF_MONO -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
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
