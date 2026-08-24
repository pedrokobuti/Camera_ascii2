package com.llama.asciicam.pipeline

/** Distortion warp types, ported 1:1 from the web tool's `distortField()` switch. */
enum class DistortionType { NONE, SINE, CIRCULAR, NOISE, TWIRL, PINCH, GLITCH }

/** Character-selection strategy: gradient ramp by luminance, or a literal word laid over the grid. */
enum class CharSource { RAMP, WORD }

/** How each cell's glyph is colored. */
enum class ColorMode { SOURCE, PALETTE, MONO }

/** Where pixels for this frame come from. */
enum class MediaSource { CAMERA, IMAGE, NOISE }

/** Procedural noise algorithms, ported from `generateNoiseValue()`. */
enum class NoiseType { WHITE, PERLIN, SIMPLEX, SPARSE, ALLIGATOR, CELLULAR, PLASMA, TURBULENCE }

enum class FontChoice(val displayName: String) {
    MONOSPACE("Monospace"),
    SERIF_MONO("Serif Monospace"),
}

/**
 * One hex color stop for the "palette" color mode.
 * `position` is stored implicitly by list order — the stops are always evenly
 * distributed across [0,1], matching the original tool's palette-stop editor.
 */
data class PaletteStop(val hex: String)

/**
 * Central mutable-ish settings bag. Ranges/defaults mirror the original web
 * tool's `<input>` elements exactly (see task spec). Backed by Compose
 * `mutableStateOf` fields in [com.llama.asciicam.ui.SettingsViewModel]; this
 * class is the plain-data snapshot passed into the pure pipeline functions.
 */
data class AsciiSettings(
    // Grid geometry
    val cols: Int = 110, // range 20..300
    val fontSizePx: Float = 22f, // range ~8..128 (device-px equivalent of the web tool's 4..64 CSS px range)
    val lineSpacingPercent: Int = 50, // range 20..150
    val charSpacingPercent: Int = 100, // range 50..300
    val font: FontChoice = FontChoice.MONOSPACE,

    // Character source
    val charSource: CharSource = CharSource.RAMP,
    val rampString: String = " .:-=+*#%@",
    val wordString: String = "#LLAMA",
    val fillChars: String = ".+$",
    val stableWord: Boolean = false,
    val wordHoldTimeSeconds: Float = 1.0f, // range 0.2..5.0

    // Edge detection
    val edgeDetectEnabled: Boolean = true,
    val edgeThreshold: Int = 35, // 0..100
    val edgeStrength: Int = 100, // 0..200
    val edgeColorEnabled: Boolean = false,
    val edgeColorArgb: Int = 0xFFFFFFFF.toInt(),

    // Distortion
    val distortionType: DistortionType = DistortionType.NONE,
    val distortionAmount: Int = 40, // 0..100
    val distortionSpeed: Int = 100, // -300..300

    // Color adjustment
    val brightness: Int = 0, // -100..100
    val contrast: Int = 0, // -100..100
    val exposure: Int = 0, // -100..100
    val saturation: Int = 100, // 0..200
    val gamma: Int = 100, // 20..300
    val invert: Boolean = false,

    // Color mode
    val colorMode: ColorMode = ColorMode.SOURCE,
    val paletteStops: List<PaletteStop> = listOf(PaletteStop("#000000"), PaletteStop("#5B8CFF")),

    // Block merge
    val merge2x2: Boolean = false,
    val merge3x3: Boolean = false,

    // Source
    val mediaSource: MediaSource = MediaSource.CAMERA,
    val useFrontCamera: Boolean = false,

    // Noise source controls
    val noiseType: NoiseType = NoiseType.PERLIN,
    val noiseScale: Float = 8f, // "feature size", range ~1..40
    val noiseSpeed: Float = 1f, // range 0..5
    val noiseFrozen: Boolean = false,
) {
    companion object {
        // Camera mode default cols is capped below the web default (110) to keep
        // the per-frame CPU pipeline (color adjust + Sobel + char selection +
        // block merge, all on the JVM, no SIMD) comfortably inside a frame
        // budget on mid-range phones. Users can still raise it via the slider.
        const val CAMERA_DEFAULT_COLS = 40
    }
}
