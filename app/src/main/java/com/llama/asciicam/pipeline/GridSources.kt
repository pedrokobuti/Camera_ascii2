package com.llama.asciicam.pipeline

import android.graphics.Bitmap

/**
 * Non-camera grid sources: a static gallery image (box-downsampled to
 * `cols x rows`), and the procedural noise generator (spec step: "generated
 * noise" alternate source, using [NoiseGenerators]).
 */
object GridSources {

    /** Box-downsamples [bitmap] to exactly cols x rows RGB samples in 0..1, matching step 3. */
    fun sampleBitmap(bitmap: Bitmap, cols: Int, rows: Int, outR: FloatArray, outG: FloatArray, outB: FloatArray) {
        // ARGB_8888 scaled bitmap gives us a cheap, reasonably high quality box/bilinear
        // downsample "for free" via Bitmap.createScaledBitmap's built-in filtering.
        val scaled = Bitmap.createScaledBitmap(bitmap, cols, rows, true)
        val pixels = IntArray(cols * rows)
        scaled.getPixels(pixels, 0, cols, 0, 0, cols, rows)
        if (scaled !== bitmap) scaled.recycle()
        for (i in pixels.indices) {
            val p = pixels[i]
            outR[i] = ((p shr 16) and 0xFF) / 255f
            outG[i] = ((p shr 8) and 0xFF) / 255f
            outB[i] = (p and 0xFF) / 255f
        }
    }

    /**
     * Procedurally fills a cols x rows grid with grayscale noise (r=g=b=noise
     * value), matching the original tool's noise-as-source render path.
     */
    fun sampleNoise(
        type: NoiseType,
        cols: Int,
        rows: Int,
        timeSeconds: Float,
        scale: Float,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
    ) {
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val idx = y * cols + x
                val v = NoiseGenerators.generateNoiseValue(type, x, y, timeSeconds, scale).coerceIn(0f, 1f)
                outR[idx] = v; outG[idx] = v; outB[idx] = v
            }
        }
    }
}
