package com.llama.asciicam.pipeline

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic procedural noise, ported line-for-line from the original web
 * tool's `noiseHash` / `perlin2D` / `simplex2D` / `sparseConvolution` /
 * `worley` / `plasma` / `turbulence` / `generateNoiseValue` functions.
 */
object NoiseGenerators {

    /** Shared integer hash -> 0..1. */
    fun noiseHash(ix: Int, iy: Int, seed: Int): Float {
        var n = (ix * 374761393 + iy * 668265263 + seed * 2147483647)
        n = n xor (n ushr 13)
        n *= 1274126177
        n = n xor (n ushr 16)
        // unsigned 32-bit -> 0..1
        val u = n.toLong() and 0xFFFFFFFFL
        return (u / 4294967295.0).toFloat()
    }

    // Deterministic shuffled permutation table (seeded LCG identical to the JS version).
    private val NOISE_PERM: IntArray = run {
        val p = IntArray(256) { it }
        var seed = 1337L
        fun rnd(): Double {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            return seed / 0x7fffffff.toDouble()
        }
        for (i in 255 downTo 1) {
            val j = floor(rnd() * (i + 1)).toInt()
            val tmp = p[i]; p[i] = p[j]; p[j] = tmp
        }
        val perm = IntArray(512)
        for (i in 0 until 512) perm[i] = p[i and 255]
        perm
    }

    private fun fade(t: Float): Float = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)

    private val GRAD2 = arrayOf(
        floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, -1f),
        floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f),
    )
    private fun grad2(hash: Int, x: Float, y: Float): Float {
        val g = GRAD2[hash and 7]
        return g[0] * x + g[1] * y
    }

    fun perlin2D(x: Float, y: Float): Float {
        val X = floor(x).toInt() and 255
        val Y = floor(y).toInt() and 255
        val xf = x - floor(x)
        val yf = y - floor(y)
        val u = fade(xf)
        val v = fade(yf)
        val p = NOISE_PERM
        val aa = p[p[X] + Y]
        val ab = p[p[X] + Y + 1]
        val ba = p[p[X + 1] + Y]
        val bb = p[p[X + 1] + Y + 1]
        val x1 = lerp(grad2(aa, xf, yf), grad2(ba, xf - 1, yf), u)
        val x2 = lerp(grad2(ab, xf, yf - 1), grad2(bb, xf - 1, yf - 1), u)
        return lerp(x1, x2, v) * 0.7f + 0.5f
    }

    private val SIMPLEX_F2 = (0.5 * (sqrt(3.0) - 1)).toFloat()
    private val SIMPLEX_G2 = ((3 - sqrt(3.0)) / 6).toFloat()
    private val SIMPLEX_GRAD = arrayOf(
        floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, -1f),
        floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f),
        floatArrayOf(0f, 1f), floatArrayOf(0f, -1f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f),
    )
    private fun simplexGrad(i: Int, j: Int): Int {
        val idx = (NOISE_PERM[i and 255] + j) and 511
        return NOISE_PERM[idx] % 12
    }

    fun simplex2D(xin: Float, yin: Float): Float {
        val s = (xin + yin) * SIMPLEX_F2
        val i = floor(xin + s).toInt()
        val j = floor(yin + s).toInt()
        val t = (i + j) * SIMPLEX_G2
        val X0 = i - t
        val Y0 = j - t
        val x0 = xin - X0
        val y0 = yin - Y0
        val i1: Int; val j1: Int
        if (x0 > y0) { i1 = 1; j1 = 0 } else { i1 = 0; j1 = 1 }
        val x1 = x0 - i1 + SIMPLEX_G2
        val y1 = y0 - j1 + SIMPLEX_G2
        val x2 = x0 - 1 + 2 * SIMPLEX_G2
        val y2 = y0 - 1 + 2 * SIMPLEX_G2
        val gi0 = simplexGrad(i, j)
        val gi1 = simplexGrad(i + i1, j + j1)
        val gi2 = simplexGrad(i + 1, j + 1)
        fun contrib(gi: Int, x: Float, y: Float): Float {
            var t0 = 0.5f - x * x - y * y
            if (t0 < 0) return 0f
            t0 *= t0
            val g = SIMPLEX_GRAD[gi]
            return t0 * t0 * (g[0] * x + g[1] * y)
        }
        val n = contrib(gi0, x0, y0) + contrib(gi1, x1, y1) + contrib(gi2, x2, y2)
        return n * 35f + 0.5f
    }

    fun sparseConvolution(x: Float, y: Float): Float {
        val cellSize = 1f
        val cx = floor(x / cellSize).toInt()
        val cy = floor(y / cellSize).toInt()
        var sum = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val gx = cx + dx
                val gy = cy + dy
                if (noiseHash(gx, gy, 101) > 0.35f) continue
                val ix = (gx + noiseHash(gx, gy, 202)) * cellSize
                val iy = (gy + noiseHash(gx, gy, 303)) * cellSize
                val ddx = x - ix
                val ddy = y - iy
                val dist = sqrt(ddx * ddx + ddy * ddy)
                if (dist < 1f) {
                    val weight = noiseHash(gx, gy, 404) * 2f - 1f
                    sum += weight * cos(dist * Math.PI.toFloat() / 2f)
                }
            }
        }
        return min(1f, max(0f, sum * 0.5f + 0.5f))
    }

    data class Worley(val f1: Float, val f2: Float, val cellId: Float)

    fun worley(x: Float, y: Float): Worley {
        val cx = floor(x).toInt()
        val cy = floor(y).toInt()
        var f1 = 1e9f
        var f2 = 1e9f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val gx = cx + dx
                val gy = cy + dy
                val px = gx + noiseHash(gx, gy, 11)
                val py = gy + noiseHash(gx, gy, 22)
                val ddx = x - px
                val ddy = y - py
                val d = ddx * ddx + ddy * ddy
                if (d < f1) { f2 = f1; f1 = d } else if (d < f2) { f2 = d }
            }
        }
        return Worley(sqrt(f1), sqrt(f2), noiseHash(cx, cy, 33))
    }

    fun plasma(x: Float, y: Float, t: Float): Float {
        val v = sin(x + t) + sin(y * 1.3f - t * 0.8f) +
            sin((x + y) * 0.7f + t * 1.4f) + sin(sqrt(x * x + y * y) * 1.1f - t * 1.7f)
        return v / 4f * 0.5f + 0.5f
    }

    fun turbulence(x: Float, y: Float): Float {
        var sum = 0f
        var amp = 0.5f
        var freq = 1f
        for (i in 0 until 4) {
            sum += (perlin2D(x * freq, y * freq) - 0.5f) * amp
            amp *= 0.5f
            freq *= 2f
        }
        return min(1f, max(0f, sum + 0.5f))
    }

    /**
     * [cellX]/[cellY] are raw grid-cell coordinates (only WHITE uses these directly,
     * one hashed value per cell by design — it's meant to look like per-pixel static
     * regardless of grid resolution). [physX]/[physY] are cell coordinates normalized
     * to a fixed reference column count (see [GridSources.sampleNoise]) so that the
     * *visual* feature size the other noise types produce, driven by [scale], stays
     * constant on screen as `cols` changes — otherwise the same `scale` value packs
     * more or fewer noise cells into the same physical width depending on `cols`,
     * and "changing Columns" would look like it's also changing the noise Scale.
     * [t] is the (speed-scaled) noise clock.
     */
    fun generateNoiseValue(type: NoiseType, cellX: Int, cellY: Int, physX: Float, physY: Float, t: Float, scale: Float): Float {
        val nx = physX / scale
        val ny = physY / scale
        return when (type) {
            NoiseType.WHITE -> noiseHash(cellX, cellY, floor(t * 8).toInt())
            NoiseType.PERLIN -> perlin2D(nx + t * 0.3f, ny + t * 0.2f)
            NoiseType.SIMPLEX -> simplex2D(nx + t * 0.25f, ny - t * 0.18f)
            NoiseType.SPARSE -> sparseConvolution(nx + t * 0.3f, ny + t * 0.2f)
            NoiseType.ALLIGATOR -> {
                val w = worley(nx + t * 0.15f, ny + t * 0.1f)
                val edge = min(1f, (w.f2 - w.f1) * 3f)
                val mottle = w.cellId * 0.6f + 0.2f
                edge * 0.7f + mottle * 0.3f
            }
            NoiseType.CELLULAR -> {
                val w = worley(nx + t * 0.15f, ny + t * 0.1f)
                min(1f, w.f1)
            }
            NoiseType.PLASMA -> plasma(nx, ny, t)
            NoiseType.TURBULENCE -> turbulence(nx + t * 0.2f, ny + t * 0.15f)
        }
    }

    const val NOISE_ASPECT_W = 4
    const val NOISE_ASPECT_H = 3

    /** Reference column count for [GridSources.sampleNoise]'s physical-coordinate
     * normalization — matches [AsciiSettings.CAMERA_DEFAULT_COLS] so the default
     * "Scale" behavior is unchanged from before this normalization was added. */
    const val REFERENCE_COLS = 40f
}
