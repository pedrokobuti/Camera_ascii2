package com.llama.asciicam.pipeline

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext

/**
 * Draws one [AsciiFrameResult] using `nativeCanvas.drawText` per visible
 * cell — the Android equivalent of the original tool's per-cell
 * `ctx.fillText` with `textAlign = "center"`, `textBaseline = "middle"`.
 * Android's Paint has no "middle" baseline mode, so we replicate it by
 * shifting the draw baseline by the font's measured vertical-center offset
 * (see [GlyphMetrics.measureBaselineOffsetRatio]).
 *
 * No rescaling happens here: [GridGeometry.cellW]/[GridGeometry.fontSizePx]
 * are already solved (in [AsciiPipeline.computeGridGeometry]) so that `cols`
 * columns exactly span the live viewport width, so this draws 1:1 at that
 * size. `rows` (from the source's aspect ratio) may not exactly match the
 * viewport height, so the content is centered vertically and cropped
 * (clipRect) if it overflows.
 */
@Composable
fun AsciiCanvas(
    frame: AsciiFrameResult?,
    geometry: GridGeometry?,
    font: FontChoice,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val typeface = remember(font) { GlyphMetrics.typefaceFor(context, font) }
    val baselineRatio = remember(font) { GlyphMetrics.measureBaselineOffsetRatio(typeface) }
    val paint = remember(typeface) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = modifier) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.drawColor(backgroundColor.toArgb())
            if (frame == null || geometry == null || geometry.cols <= 0 || geometry.rows <= 0) return@drawIntoCanvas

            val contentW = geometry.cols * geometry.cellW
            val contentH = geometry.rows * geometry.rowPitch
            if (contentW <= 0f || contentH <= 0f || geometry.fontSizePx <= 0f) return@drawIntoCanvas

            val offsetX = (size.width - contentW) / 2f
            val offsetY = (size.height - contentH) / 2f

            val save = native.save()
            native.clipRect(0f, 0f, size.width, size.height)
            native.translate(offsetX, offsetY)

            val cols = geometry.cols
            val rows = geometry.rows
            val cellW = geometry.cellW
            val rowPitch = geometry.rowPitch
            val baseFontSize = geometry.fontSizePx

            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val idx = y * cols + x
                    val span = frame.span[idx]
                    if (span <= 0) continue
                    val ch = frame.chars[idx]
                    if (ch == ' ') continue
                    val fontSize = baseFontSize * span
                    paint.textSize = fontSize
                    paint.color = frame.colors[idx]
                    val cx = (x + span / 2f) * cellW
                    val cy = (y + span / 2f) * rowPitch
                    val baselineY = cy - baselineRatio * fontSize
                    native.drawText(ch.toString(), cx, baselineY, paint)
                }
            }
            native.restoreToCount(save)
        }
    }
}

private fun Color.toArgb(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
