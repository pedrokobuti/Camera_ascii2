package com.llama.asciicam.pipeline

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max

/**
 * CameraX [ImageAnalysis.Analyzer] that downsamples a YUV_420_888 frame
 * directly into a `cols x rows` grid of raw 0..1 RGB samples — the pipeline's
 * "grid downsampling" step (spec step 3) for the camera source. Avoids ever
 * materializing a full-resolution Bitmap: each output cell is the box-average
 * of the source pixels that land in it, read straight from the Y/U/V planes.
 *
 * Reused buffers ([outR]/[outG]/[outB]) are grown, not reallocated, when the
 * grid size is stable across frames — the common case.
 */
class CameraFrameAnalyzer(
    private val cols: () -> Int,
    private val rows: () -> Int,
    private val mirror: () -> Boolean,
    private val onFrame: (r: FloatArray, g: FloatArray, b: FloatArray, cols: Int, rows: Int, srcW: Int, srcH: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    private var outR = FloatArray(0)
    private var outG = FloatArray(0)
    private var outB = FloatArray(0)
    private var countBuf = IntArray(0)
    private var sumR = FloatArray(0)
    private var sumG = FloatArray(0)
    private var sumB = FloatArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val c = max(1, cols())
            val rws = max(1, rows())
            val n = c * rws
            if (outR.size != n) {
                outR = FloatArray(n); outG = FloatArray(n); outB = FloatArray(n)
                countBuf = IntArray(n); sumR = FloatArray(n); sumG = FloatArray(n); sumB = FloatArray(n)
            } else {
                countBuf.fill(0); sumR.fill(0f); sumG.fill(0f); sumB.fill(0f)
            }

            val rotation = image.imageInfo.rotationDegrees
            val doMirror = mirror()

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            val rawW = image.width
            val rawH = image.height
            // Output (post-rotation) logical dimensions.
            val outW = if (rotation == 90 || rotation == 270) rawH else rawW
            val outH = if (rotation == 90 || rotation == 270) rawW else rawH

            var yIdx: Int
            var uvRow: Int
            var uvCol: Int

            // Iterate every source pixel is too slow at full camera resolution;
            // stride-sample instead, aiming for roughly one sample per output cell
            // times a small supersampling factor for reasonable box-average quality.
            val sampleStepX = max(1, outW / (c * 3))
            val sampleStepY = max(1, outH / (rws * 3))

            var py = 0
            while (py < outH) {
                var px = 0
                while (px < outW) {
                    // Map output (post-rotation) pixel -> raw sensor pixel.
                    val rawX: Int
                    val rawY: Int
                    when (rotation) {
                        90 -> { rawX = py; rawY = rawH - 1 - px }
                        180 -> { rawX = rawW - 1 - px; rawY = rawH - 1 - py }
                        270 -> { rawX = outW - 1 - py; rawY = px }
                        else -> { rawX = px; rawY = py }
                    }
                    if (rawX in 0 until rawW && rawY in 0 until rawH) {
                        yIdx = rawY * yRowStride + rawX * yPixelStride
                        uvRow = rawY / 2
                        uvCol = rawX / 2
                        val uvIdx = uvRow * uvRowStride + uvCol * uvPixelStride
                        if (yIdx < yBuf.limit() && uvIdx < uBuf.limit() && uvIdx < vBuf.limit()) {
                            val yVal = (yBuf.get(yIdx).toInt() and 0xFF)
                            val uVal = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                            val vVal = (vBuf.get(uvIdx).toInt() and 0xFF) - 128

                            val yF = yVal - 16
                            val r = 1.164f * yF + 1.596f * vVal
                            val g = 1.164f * yF - 0.392f * uVal - 0.813f * vVal
                            val b = 1.164f * yF + 2.017f * uVal

                            var cellX = (px * c) / outW
                            val cellY = (py * rws) / outH
                            if (doMirror) cellX = c - 1 - cellX
                            cellX = cellX.coerceIn(0, c - 1)
                            val cellYc = cellY.coerceIn(0, rws - 1)
                            val cellIdx = cellYc * c + cellX
                            sumR[cellIdx] += (r / 255f).coerceIn(0f, 1f)
                            sumG[cellIdx] += (g / 255f).coerceIn(0f, 1f)
                            sumB[cellIdx] += (b / 255f).coerceIn(0f, 1f)
                            countBuf[cellIdx]++
                        }
                    }
                    px += sampleStepX
                }
                py += sampleStepY
            }

            for (i in 0 until n) {
                val cnt = countBuf[i]
                if (cnt > 0) {
                    outR[i] = sumR[i] / cnt
                    outG[i] = sumG[i] / cnt
                    outB[i] = sumB[i] / cnt
                } else {
                    outR[i] = 0f; outG[i] = 0f; outB[i] = 0f
                }
            }

            onFrame(outR, outG, outB, c, rws, outW, outH)
        } finally {
            image.close()
        }
    }
}
