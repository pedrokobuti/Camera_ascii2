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

    /** Dense, full-height characters — the ones that set a font's apparent
     * weight in ASCII art — used to measure ink extent in [inkHeightRatio]. */
    private const val INK_PROBE_CHARS = "#@MWX8"

    // Cached only on success — a transient load failure must NOT get stuck
    // here, since that would silently poison every future caller (including
    // ones on a different thread, like VideoRecorder's) into the fallback
    // font forever for the rest of the process. @Volatile so a load that
    // completes on one thread is visible to callers on another right away.
    @Volatile private var cachedModernDos: Typeface? = null

    fun typefaceFor(context: Context, font: FontChoice): Typeface =
        if (font == FontChoice.MODERN_DOS) {
            cachedModernDos ?: loadModernDos(context).also { cachedModernDos = it } ?: Typeface.MONOSPACE
        } else {
            // Typeface.create maintains its own internal cache, and falls back
            // to the device default for a family it doesn't have.
            font.systemFamily?.let { Typeface.create(it, font.systemStyle) } ?: Typeface.MONOSPACE
        }

    // Resources.getFont() is the plain platform API (available since API 26,
    // this app's minSdk) for loading a font resource — used directly instead
    // of AndroidX's ResourcesCompat.getFont(), which layers on a
    // compatibility shim (with its own async-callback machinery and internal
    // cache) that this minSdk doesn't need and that has had known edge cases
    // returning null/a substitute font on some devices.
    //
    // There is deliberately no "load a second, independent copy" variant. One
    // used to exist, for VideoRecorder only, on a never-confirmed theory that
    // sharing a Typeface across threads was unsafe. It isn't — Typeface is
    // immutable and safe to *draw* with concurrently; it's Paint that can't be
    // shared, and each drawing path already builds its own. Meanwhile the
    // correlation was exact: the recorder was the only caller that loaded its
    // own copy, and recorded video was the only output that ever came out in
    // the wrong font (Roboto/Droid Sans Mono's glyph shapes — a slashed zero
    // and a 6-point asterisk — i.e. Typeface.MONOSPACE, the fallback returned
    // when this function fails). Everything drawing from the single cached
    // instance has always been correct, so there is now exactly one way to get
    // a Typeface here and every path shares it.
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
     * Multiplier for the drawn text size that keeps [font]'s ink inside the row
     * [AsciiPipeline.computeGridGeometry] already reserved for it.
     *
     * Grid layout solves font size purely from advance width (see
     * [measureCharAspect] / [AsciiPipeline.computeGridGeometry]), so a font
     * with a *narrower* natural advance needs a *larger* size to fill the same
     * column — and since every other dimension of a font scales with its size
     * too, that larger size also makes its cap-height/stems taller in absolute
     * pixels. Android's built-in "monospace" is markedly narrower than
     * "serif-monospace", so it needs markedly more size-inflation to fill a
     * column, and ends up with markedly taller ink for it. Comparing measured
     * ink height against the reference (Modern DOS) at each font's own solved
     * size catches exactly that, whatever the underlying cause, and pulls the
     * *drawn* glyph back down to size — [AsciiPipeline.computeGridGeometry]
     * applies this only to the drawn size, never to [GridGeometry.rowPitch] or
     * row count, so the row this font earned by being narrower stays put; only
     * how much of it the glyph actually fills changes. Returns exactly 1.0 for
     * Modern DOS, so the default look is untouched.
     */
    fun fontSizeScaleFor(context: Context, font: FontChoice): Float {
        if (font == FontChoice.MODERN_DOS) return 1f
        cachedScales[font]?.let { return it }
        // Each face's own solved size at a shared PROBE_SIZE-wide column,
        // mirroring AsciiPipeline.computeGridGeometry's
        // fontSize = baseCellW / charAspect (with baseCellW == PROBE_SIZE
        // here) — the size whose ink actually matters, not PROBE_SIZE itself.
        fun solvedSize(typeface: Typeface) = PROBE_SIZE / measureCharAspect(typeface).coerceAtLeast(0.01f)
        val referenceFace = typefaceFor(context, FontChoice.MODERN_DOS)
        val ownFace = typefaceFor(context, font)
        val inkRef = inkHeightRatio(referenceFace, solvedSize(referenceFace))
        val inkOwn = inkHeightRatio(ownFace, solvedSize(ownFace))
        // Never scales *up*: a face whose ink is already shorter than the
        // reference's at its own solved size is left alone.
        val scale = (if (inkOwn <= 0.01f) 1f else inkRef / inkOwn).coerceIn(0.35f, 1f)
        cachedScales[font] = scale
        return scale
    }

    private val cachedScales = java.util.concurrent.ConcurrentHashMap<FontChoice, Float>()

    /**
     * Height of the inked pixels of a representative dense glyph set at
     * [atSize], relative to [PROBE_SIZE] — i.e. the fraction of a *reference*
     * text size the glyph's ink occupies, evaluated at whatever size this font
     * actually gets drawn at. Uses the union of a few characters rather than
     * one, since any single glyph's height is an accident of that font's
     * design.
     */
    private fun inkHeightRatio(typeface: Typeface, atSize: Float): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = atSize
        }
        val bounds = android.graphics.Rect()
        var maxHeight = 0
        for (ch in INK_PROBE_CHARS) {
            paint.getTextBounds(ch.toString(), 0, 1, bounds)
            if (bounds.height() > maxHeight) maxHeight = bounds.height()
        }
        return maxHeight / PROBE_SIZE
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
