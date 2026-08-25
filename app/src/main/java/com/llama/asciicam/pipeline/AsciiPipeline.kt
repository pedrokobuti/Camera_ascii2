package com.llama.asciicam.pipeline

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/** Cell geometry derived from font metrics + user spacing sliders (pipeline step 1). */
data class GridGeometry(
    val cols: Int,
    val rows: Int,
    val cellW: Float,
    val rowPitch: Float,
    val fontSizePx: Float,
)

/**
 * Output of one full pipeline pass: parallel arrays, one slot per grid cell in
 * row-major order. [span] encodes block-merge results: 1 = ordinary cell, 2/3
 * = this cell is the top-left origin of a merged NxN block drawn as one big
 * glyph, 0 = this cell is consumed by a merged block and must not be drawn.
 */
class AsciiFrameResult(val cols: Int, val rows: Int) {
    val chars = CharArray(cols * rows) { ' ' }
    val colors = IntArray(cols * rows)
    val span = IntArray(cols * rows) { 1 }
}

/**
 * Persistent scratch state reused frame-to-frame: EMA smoothing history, the
 * distortion clock, the stable-word letter cache, and per-frame scratch
 * buffers (kept here, not reallocated per call, to avoid hot-path churn).
 * Call [ensureSize] every frame; it only reallocates when the grid dimensions
 * actually change.
 */
class PipelineState {
    var cols = -1
        private set
    var rows = -1
        private set

    var hasPrevFrame = false
    var distortionClockSeconds = 0f

    // EMA smoothing history (post color-adjustment values from the previous frame).
    var prevLum = FloatArray(0); var prevR = FloatArray(0); var prevG = FloatArray(0); var prevB = FloatArray(0)

    // Per-frame scratch (avoid per-call allocation).
    var adjR = FloatArray(0); var adjG = FloatArray(0); var adjB = FloatArray(0); var adjLum = FloatArray(0)
    var smLum = FloatArray(0); var smR = FloatArray(0); var smG = FloatArray(0); var smB = FloatArray(0)
    var distLum = FloatArray(0); var distR = FloatArray(0); var distG = FloatArray(0); var distB = FloatArray(0)
    var mag = FloatArray(0); var ang = FloatArray(0); var vArr = FloatArray(0)
    var isEdge = BooleanArray(0); var edgeChar = CharArray(0)
    var claimed = BooleanArray(0)

    // Stable-word cache.
    var wordAssignedChar = CharArray(0)
    var wordIsLetterCell = BooleanArray(0)
    var wordElapsedSinceCommit = Float.MAX_VALUE

    fun ensureSize(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols; this.rows = rows
        val n = cols * rows
        prevLum = FloatArray(n); prevR = FloatArray(n); prevG = FloatArray(n); prevB = FloatArray(n)
        adjR = FloatArray(n); adjG = FloatArray(n); adjB = FloatArray(n); adjLum = FloatArray(n)
        smLum = FloatArray(n); smR = FloatArray(n); smG = FloatArray(n); smB = FloatArray(n)
        distLum = FloatArray(n); distR = FloatArray(n); distG = FloatArray(n); distB = FloatArray(n)
        mag = FloatArray(n); ang = FloatArray(n); vArr = FloatArray(n)
        isEdge = BooleanArray(n); edgeChar = CharArray(n)
        claimed = BooleanArray(n)
        wordAssignedChar = CharArray(n) { ' ' }
        wordIsLetterCell = BooleanArray(n)
        hasPrevFrame = false
        wordElapsedSinceCommit = Float.MAX_VALUE
    }

    /** Force a full reset (e.g. switching media source) without changing dimensions. */
    fun reset() {
        hasPrevFrame = false
        wordElapsedSinceCommit = Float.MAX_VALUE
        wordIsLetterCell.fill(false)
    }
}

/**
 * The pure(ish) per-frame ASCII conversion pipeline: color adjustment ->
 * temporal smoothing -> distortion warp -> Sobel edge detection -> character
 * selection -> block merge -> per-cell color. Ported step-for-step from the
 * original web tool (see task spec / original `render()` + helpers).
 *
 * Input is already a `cols x rows` grid of raw linear-ish 0..1 RGB samples
 * (the "grid downsampling" step happens upstream — see CameraFrameAnalyzer /
 * image & noise sources — since that step differs a lot per source type).
 */
object AsciiPipeline {

    /** Hard ceiling on grid columns, enforced here regardless of what's stored in
     * [AsciiSettings.cols] — higher counts made the per-frame CPU pipeline (color
     * adjust + Sobel + char selection + block merge, all on the JVM) miss its frame
     * budget badly enough to crash on real devices. The Columns slider is capped
     * to the same value, but this is the actual safety net. */
    const val MAX_COLS = 180

    /**
     * [viewportWidthPx] is the live on-screen viewfinder width. Font size is no
     * longer a separate user setting — it's solved for here so the grid always
     * fills exactly that width for the current `cols`, i.e. `cols` is now the
     * only density/zoom control (more columns = smaller characters covering
     * the same screen width, not a smaller image).
     */
    fun computeGridGeometry(settings: AsciiSettings, sourceWidth: Int, sourceHeight: Int, charAspect: Float, viewportWidthPx: Float): GridGeometry {
        val cols = settings.cols.coerceIn(1, MAX_COLS)
        val lineSpacingFactor = settings.lineSpacingPercent / 100f
        val charSpacingFactor = settings.charSpacingPercent / 100f
        val cellW = (viewportWidthPx / cols).coerceAtLeast(0.5f)
        val baseCellW = cellW / charSpacingFactor
        val fontSizePx = (baseCellW / charAspect.coerceAtLeast(0.01f)).coerceAtLeast(0.5f)
        val rowPitch = (fontSizePx * lineSpacingFactor).coerceAtLeast(0.5f)
        val srcAspect = if (sourceWidth > 0) sourceHeight.toFloat() / sourceWidth.toFloat() else 0.75f
        val rows = max(1, round(cols * srcAspect * (baseCellW / rowPitch)).toInt())
        return GridGeometry(cols, rows, cellW, rowPitch, fontSizePx)
    }

    fun process(
        rawR: FloatArray,
        rawG: FloatArray,
        rawB: FloatArray,
        cols: Int,
        rows: Int,
        settings: AsciiSettings,
        state: PipelineState,
        dtSeconds: Float,
        applyTemporalSmoothing: Boolean,
        sortedRamp: String,
    ): AsciiFrameResult {
        state.ensureSize(cols, rows)
        val n = cols * rows

        // ---------- step 2: per-cell color adjustment ----------
        val brightness_ = settings.brightness / 100f * 0.5f
        val contrastFactor = 1f + settings.contrast / 100f
        val exposureFactor = 2f.pow(settings.exposure / 50f)
        val satFactor = settings.saturation / 100f
        val gamma_ = max(0.01f, settings.gamma / 100f)
        val invGamma = 1f / gamma_

        val adjR = state.adjR; val adjG = state.adjG; val adjB = state.adjB; val adjLum = state.adjLum
        for (i in 0 until n) {
            var r = rawR[i] * exposureFactor
            var g = rawG[i] * exposureFactor
            var b = rawB[i] * exposureFactor
            r += brightness_; g += brightness_; b += brightness_
            r = (r - 0.5f) * contrastFactor + 0.5f
            g = (g - 0.5f) * contrastFactor + 0.5f
            b = (b - 0.5f) * contrastFactor + 0.5f
            val lum0 = r * 0.299f + g * 0.587f + b * 0.114f
            r = lum0 + (r - lum0) * satFactor
            g = lum0 + (g - lum0) * satFactor
            b = lum0 + (b - lum0) * satFactor
            r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
            if (invGamma != 1f) {
                r = r.pow(invGamma); g = g.pow(invGamma); b = b.pow(invGamma)
            }
            adjR[i] = r; adjG[i] = g; adjB[i] = b
            adjLum[i] = r * 0.299f + g * 0.587f + b * 0.114f
        }

        // ---------- step 4: temporal smoothing (camera/video sources only) ----------
        val smLum: FloatArray; val smR: FloatArray; val smG: FloatArray; val smB: FloatArray
        if (applyTemporalSmoothing) {
            smLum = state.smLum; smR = state.smR; smG = state.smG; smB = state.smB
            if (state.hasPrevFrame) {
                for (i in 0 until n) {
                    smLum[i] = state.prevLum[i] * 0.55f + adjLum[i] * 0.45f
                    smR[i] = state.prevR[i] * 0.55f + adjR[i] * 0.45f
                    smG[i] = state.prevG[i] * 0.55f + adjG[i] * 0.45f
                    smB[i] = state.prevB[i] * 0.55f + adjB[i] * 0.45f
                }
            } else {
                System.arraycopy(adjLum, 0, smLum, 0, n)
                System.arraycopy(adjR, 0, smR, 0, n)
                System.arraycopy(adjG, 0, smG, 0, n)
                System.arraycopy(adjB, 0, smB, 0, n)
            }
            System.arraycopy(smLum, 0, state.prevLum, 0, n)
            System.arraycopy(smR, 0, state.prevR, 0, n)
            System.arraycopy(smG, 0, state.prevG, 0, n)
            System.arraycopy(smB, 0, state.prevB, 0, n)
            state.hasPrevFrame = true
        } else {
            smLum = adjLum; smR = adjR; smG = adjG; smB = adjB
            state.hasPrevFrame = false
        }

        // ---------- step 5: distortion ----------
        val distLum: FloatArray; val distR: FloatArray; val distG: FloatArray; val distB: FloatArray
        val clampedDt = dtSeconds.coerceIn(0f, 0.1f)
        state.distortionClockSeconds += clampedDt * (settings.distortionSpeed / 100f)
        if (settings.distortionType == DistortionType.NONE) {
            distLum = smLum; distR = smR; distG = smG; distB = smB
        } else {
            distLum = state.distLum; distR = state.distR; distG = state.distG; distB = state.distB
            val amt = settings.distortionAmount / 100f
            val time = state.distortionClockSeconds
            val cx = (cols - 1) / 2f
            val cy = (rows - 1) / 2f
            val minDim = min(cols, rows).toFloat()
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    var dx = 0f
                    var dy = 0f
                    when (settings.distortionType) {
                        DistortionType.SINE -> {
                            dx = amt * cols * 0.06f * sin(y * 0.35f + time * 2f)
                            dy = amt * rows * 0.06f * sin(x * 0.35f + time * 2.3f)
                        }
                        DistortionType.CIRCULAR -> {
                            val ddx = x - cx; val ddy = y - cy
                            val dist = hypot(ddx, ddy)
                            val angle = atan2(ddy, ddx)
                            val ripple = amt * minDim * 0.06f * sin(dist * 0.5f - time * 3f)
                            dx = ripple * cos(angle); dy = ripple * sin(angle)
                        }
                        DistortionType.NOISE -> {
                            dx = (hashNoiseF(x, y, time, 0f) - 0.5f) * 2f * amt * minDim * 0.08f
                            dy = (hashNoiseF(x, y, time, 97.3f) - 0.5f) * 2f * amt * minDim * 0.08f
                        }
                        DistortionType.TWIRL -> {
                            val ddx = x - cx; val ddy = y - cy
                            val dist = hypot(ddx, ddy)
                            val maxDist = minDim * 0.6f
                            val twirlFactor = max(0f, 1f - dist / maxDist)
                            val twist = amt * 3f + amt * 2f * sin(time * 0.6f)
                            val angle = atan2(ddy, ddx) + twirlFactor * twist
                            dx = cx + dist * cos(angle) - x
                            dy = cy + dist * sin(angle) - y
                        }
                        DistortionType.PINCH -> {
                            val ddx = x - cx; val ddy = y - cy
                            val dist = hypot(ddx, ddy)
                            val maxDist = minDim * 0.6f
                            val normDist = min(1f, dist / maxDist)
                            val pinchAmount = amt * 0.6f + amt * 0.5f * sin(time * 1.5f)
                            val factor = max(normDist, 0.0001f).pow(1f + pinchAmount)
                            val newDist = factor * maxDist
                            val angle = atan2(ddy, ddx)
                            dx = cos(angle) * newDist - ddx
                            dy = sin(angle) * newDist - ddy
                        }
                        DistortionType.GLITCH -> {
                            val bandRows = max(1, round(minDim * 0.05f).toInt())
                            val band = y / bandRows
                            val glitchTick = floor(time * 4f)
                            if (hashNoiseF(band, 1, glitchTick, 77f) < 0.05f + 0.3f * amt) {
                                dx = (hashNoiseF(band, 0, glitchTick, 55f) - 0.5f) * 2f * amt * cols * 0.15f
                            }
                        }
                        DistortionType.NONE -> {}
                    }
                    val sx = (x + dx).coerceIn(0f, (cols - 1).toFloat())
                    val sy = (y + dy).coerceIn(0f, (rows - 1).toFloat())
                    val idx = y * cols + x
                    distLum[idx] = sampleBilinear(smLum, cols, rows, sx, sy)
                    distR[idx] = sampleBilinear(smR, cols, rows, sx, sy)
                    distG[idx] = sampleBilinear(smG, cols, rows, sx, sy)
                    distB[idx] = sampleBilinear(smB, cols, rows, sx, sy)
                }
            }
        }

        // ---------- step 6: Sobel edge detection ----------
        val mag = state.mag; val ang = state.ang
        var maxMag = 0f
        if (settings.edgeDetectEnabled) {
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val l00 = sampleClamped(distLum, cols, rows, x - 1, y - 1)
                    val l10 = sampleClamped(distLum, cols, rows, x + 1, y - 1)
                    val l01 = sampleClamped(distLum, cols, rows, x - 1, y)
                    val l11 = sampleClamped(distLum, cols, rows, x + 1, y)
                    val l02 = sampleClamped(distLum, cols, rows, x - 1, y + 1)
                    val l12 = sampleClamped(distLum, cols, rows, x + 1, y + 1)
                    val lTop = sampleClamped(distLum, cols, rows, x, y - 1)
                    val lBot = sampleClamped(distLum, cols, rows, x, y + 1)
                    val gx = -l00 + l10 - 2f * l01 + 2f * l11 - l02 + l12
                    val gy = -l00 - 2f * lTop - l10 + l02 + 2f * lBot + l12
                    val idx = y * cols + x
                    val m = hypot(gx, gy)
                    mag[idx] = m
                    ang[idx] = atan2(gy, gx)
                    if (m > maxMag) maxMag = m
                }
            }
        }
        val edgeThresholdAbs = (settings.edgeThreshold / 100f) * (if (maxMag > 0f) maxMag else 1f) /
            max(settings.edgeStrength / 100f, 0.01f)

        val isEdge = state.isEdge; val edgeChar = state.edgeChar
        if (settings.edgeDetectEnabled) {
            for (i in 0 until n) {
                val m = mag[i]
                if (m >= edgeThresholdAbs && m > 0.001f) {
                    isEdge[i] = true
                    var deg = (Math.toDegrees(ang[i].toDouble()).toFloat() + 90f + 360f) % 180f
                    edgeChar[i] = when {
                        deg < 22.5f || deg >= 157.5f -> '-'
                        deg < 67.5f -> '\\'
                        deg < 112.5f -> '|'
                        else -> '/'
                    }
                } else {
                    isEdge[i] = false
                }
            }
        } else {
            isEdge.fill(false)
        }

        // ---------- luminance value used for char/threshold selection ----------
        val v = state.vArr
        for (i in 0 until n) v[i] = if (settings.invert) 1f - distLum[i] else distLum[i]

        val result = AsciiFrameResult(cols, rows)
        val chars = result.chars

        // ---------- step 7: character selection ----------
        when (settings.charSource) {
            CharSource.RAMP -> {
                val rampLen = sortedRamp.length
                for (i in 0 until n) {
                    chars[i] = if (isEdge[i]) {
                        edgeChar[i]
                    } else if (rampLen == 0) {
                        ' '
                    } else {
                        val idx = round(v[i].coerceIn(0f, 1f) * (rampLen - 1)).toInt().coerceIn(0, rampLen - 1)
                        sortedRamp[idx]
                    }
                }
            }
            CharSource.WORD -> {
                selectWordChars(chars, isEdge, edgeChar, v, cols, rows, settings, state, dtSeconds)
            }
        }

        // ---------- step 9 groundwork: per-cell color (before merge averaging) ----------
        // Colors always use the true (non-inverted) luminance/RGB — Invert ASCII
        // only flips which glyph density represents a given brightness, not what
        // color that glyph is drawn in (the glyph should still read as "the
        // color of the input").
        val edgeColorActive = settings.edgeColorMode != EdgeColorMode.OFF
        for (i in 0 until n) {
            result.colors[i] = if (edgeColorActive && isEdge[i]) {
                edgeColorFor(settings, distLum[i])
            } else {
                cellColor(settings, distR[i], distG[i], distB[i], distLum[i])
            }
        }

        // ---------- step 8: block merge (3x3 first, then 2x2) ----------
        applyBlockMerge(result, distR, distG, distB, distLum, isEdge, cols, rows, settings, state)

        return result
    }

    // ---- helpers ----

    private fun sampleClamped(grid: FloatArray, cols: Int, rows: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, cols - 1)
        val cy = y.coerceIn(0, rows - 1)
        return grid[cy * cols + cx]
    }

    private fun sampleBilinear(grid: FloatArray, cols: Int, rows: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt().coerceIn(0, cols - 1)
        val y0 = floor(y).toInt().coerceIn(0, rows - 1)
        val x1 = (x0 + 1).coerceAtMost(cols - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val fx = x - x0
        val fy = y - y0
        val v00 = grid[y0 * cols + x0]
        val v10 = grid[y0 * cols + x1]
        val v01 = grid[y1 * cols + x0]
        val v11 = grid[y1 * cols + x1]
        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return top + (bottom - top) * fy
    }

    /** Ported from the original tool's `hashNoise(x,y,t,seedOffset)` value-noise. */
    private fun hashNoiseF(x: Int, y: Int, t: Float, seedOffset: Float): Float {
        val phase0 = floor(t)
        val phase1 = phase0 + 1f
        fun valAt(phase: Float): Float {
            val s = sin(x * 127.1f + y * 311.7f + phase * 74.7f + seedOffset) * 43758.5453f
            return s - floor(s)
        }
        val n0 = valAt(phase0)
        val n1 = valAt(phase1)
        return n0 + (t - phase0) * (n1 - n0)
    }

    private fun selectWordChars(
        chars: CharArray,
        isEdge: BooleanArray,
        edgeChar: CharArray,
        v: FloatArray,
        cols: Int,
        rows: Int,
        settings: AsciiSettings,
        state: PipelineState,
        dtSeconds: Float,
    ) {
        val n = cols * rows
        val word = settings.wordString.ifEmpty { " " }
        val fills = settings.fillChars.ifEmpty { "." }
        val bgThresh = 0.06f
        val fillThresh = 0.35f

        fun classify(i: Int): Char {
            // returns ' ' for "letter cell" (needs sequential assignment), else the resolved char
            val value = v[i]
            return when {
                value < bgThresh -> ' '
                value < fillThresh -> {
                    val t = ((value - bgThresh) / (fillThresh - bgThresh)).coerceIn(0f, 0.999f)
                    val fi = (t * fills.length).toInt().coerceIn(0, fills.length - 1)
                    fills[fi]
                }
                else -> ' '
            }
        }

        var commit = !settings.stableWord
        if (settings.stableWord) {
            state.wordElapsedSinceCommit += dtSeconds.coerceAtLeast(0f)
            if (state.wordElapsedSinceCommit >= settings.wordHoldTimeSeconds) commit = true
        }

        if (commit) {
            var wordIdx = 0
            for (i in 0 until n) {
                if (isEdge[i]) { state.wordIsLetterCell[i] = false; continue }
                val resolved = classify(i)
                if (resolved == ' ') {
                    val letter = word[wordIdx % word.length]
                    wordIdx++
                    state.wordAssignedChar[i] = letter
                    state.wordIsLetterCell[i] = true
                } else {
                    state.wordIsLetterCell[i] = false
                }
            }
            if (settings.stableWord) state.wordElapsedSinceCommit = 0f
        }

        val brightestFill = fills[fills.length - 1]
        for (i in 0 until n) {
            chars[i] = when {
                isEdge[i] -> edgeChar[i]
                else -> {
                    val resolved = classify(i)
                    when {
                        resolved != ' ' -> resolved
                        !settings.stableWord -> state.wordAssignedChar[i] // just committed this frame
                        state.wordIsLetterCell[i] -> state.wordAssignedChar[i]
                        else -> brightestFill // newly-lit since last commit
                    }
                }
            }
        }
    }

    private fun cellColor(settings: AsciiSettings, r: Float, g: Float, b: Float, v: Float): Int {
        return when (settings.colorMode) {
            ColorMode.SOURCE -> argb(255, r, g, b)
            ColorMode.MONO -> 0xFFE8E8EA.toInt()
            ColorMode.PALETTE -> paletteColor(settings.paletteStops, v)
            ColorMode.IMPOSTER -> paletteColor(IMPOSTER_PALETTE_STOPS, v)
        }
    }

    /** Mirrors [IMPOSTER_PALETTE_STOPS] in reverse order, so an edge cell never
     * lands on the same color as a non-edge cell at the same brightness. */
    private fun edgeColorFor(settings: AsciiSettings, v: Float): Int = when (settings.edgeColorMode) {
        EdgeColorMode.CUSTOM -> settings.edgeColorArgb
        EdgeColorMode.IMPOSTER -> paletteColor(IMPOSTER_PALETTE_STOPS.asReversed(), v)
        EdgeColorMode.OFF -> settings.edgeColorArgb // unused by callers; OFF is gated before this is called
    }

    private fun paletteColor(stops: List<PaletteStop>, v: Float): Int {
        if (stops.isEmpty()) return 0xFFFFFFFF.toInt()
        if (stops.size == 1) return parseHexColor(stops[0].hex)
        val t = v.coerceIn(0f, 1f) * (stops.size - 1)
        val i0 = floor(t).toInt().coerceIn(0, stops.size - 2)
        val i1 = i0 + 1
        val frac = t - i0
        val c0 = parseHexColor(stops[i0].hex)
        val c1 = parseHexColor(stops[i1].hex)
        val r = lerpInt((c0 shr 16) and 0xFF, (c1 shr 16) and 0xFF, frac)
        val g = lerpInt((c0 shr 8) and 0xFF, (c1 shr 8) and 0xFF, frac)
        val b = lerpInt(c0 and 0xFF, c1 and 0xFF, frac)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerpInt(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)

    fun parseHexColor(hex: String): Int {
        val cleaned = hex.removePrefix("#")
        return try {
            when (cleaned.length) {
                6 -> (0xFF shl 24) or cleaned.toInt(16)
                8 -> cleaned.toLong(16).toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        } catch (e: NumberFormatException) {
            0xFFFFFFFF.toInt()
        }
    }

    private fun argb(a: Int, r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun applyBlockMerge(
        result: AsciiFrameResult,
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        v: FloatArray,
        isEdge: BooleanArray,
        cols: Int,
        rows: Int,
        settings: AsciiSettings,
        state: PipelineState,
    ) {
        if (!settings.merge3x3 && !settings.merge2x2) return
        val claimed = state.claimed
        claimed.fill(false)
        val chars = result.chars
        val colors = result.colors
        val span = result.span

        fun tryMerge(blockSize: Int) {
            var y = 0
            while (y + blockSize <= rows) {
                var x = 0
                while (x + blockSize <= cols) {
                    val originIdx = y * cols + x
                    if (!claimed[originIdx]) {
                        val ch = chars[originIdx]
                        var uniform = ch != ' '
                        if (uniform) {
                            outer@ for (dy in 0 until blockSize) {
                                for (dx in 0 until blockSize) {
                                    val idx = (y + dy) * cols + (x + dx)
                                    if (claimed[idx] || chars[idx] != ch) { uniform = false; break@outer }
                                }
                            }
                        }
                        if (uniform) {
                            var sumR = 0f; var sumG = 0f; var sumB = 0f
                            var anyEdge = false
                            val count = blockSize * blockSize
                            for (dy in 0 until blockSize) {
                                for (dx in 0 until blockSize) {
                                    val idx = (y + dy) * cols + (x + dx)
                                    sumR += r[idx]; sumG += g[idx]; sumB += b[idx]
                                    if (isEdge[idx]) anyEdge = true
                                    claimed[idx] = true
                                    if (idx != originIdx) span[idx] = 0
                                }
                            }
                            span[originIdx] = blockSize
                            colors[originIdx] = if (settings.edgeColorMode != EdgeColorMode.OFF && anyEdge) {
                                edgeColorFor(settings, v[originIdx])
                            } else {
                                cellColor(settings, sumR / count, sumG / count, sumB / count, v[originIdx])
                            }
                        }
                    }
                    x += blockSize
                }
                y += blockSize
            }
        }

        if (settings.merge3x3) tryMerge(3)
        if (settings.merge2x2) tryMerge(2)
    }
}
