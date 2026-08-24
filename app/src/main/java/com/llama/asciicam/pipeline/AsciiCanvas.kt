package com.llama.asciicam.pipeline

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Draws one [AsciiFrameResult] using `nativeCanvas.drawText` per visible
 * cell — the Android equivalent of the original tool's per-cell
 * `ctx.fillText` with `textAlign = "center"`, `textBaseline = "middle"`.
 * Android's Paint has no "middle" baseline mode, so we replicate it by
 * shifting the draw baseline by the font's measured vertical-center offset
 * (see [GlyphMetrics.measureBaselineOffsetRatio]).
 *
 * Scaling: the grid is scaled so that at [AsciiSettings.DEFAULT_FONT_SIZE_PX]
 * it exactly fills the canvas width for the current `cols` setting — that
 * reference point, not the live `fontSizePx`, drives the fill scale. Moving
 * the font-size slider away from that reference then visibly zooms in
 * (bigger glyphs, grid overflows and gets cropped) or out (smaller glyphs,
 * grid shrinks inside the frame), centered on both axes. An earlier version
 * computed the fill scale directly from the live fontSizePx, which made the
 * slider a no-op: growing fontSizePx grew the content by exactly the factor
 * the fit-to-canvas scale then shrank it back by.
 */
@Composable
fun AsciiCanvas(
    frame: AsciiFrameResult?,
    geometry: GridGeometry?,
    font: FontChoice,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val typeface = remember(font) { GlyphMetrics.typefaceFor(font) }
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

            // cellW/rowPitch scale linearly with fontSizePx, so this ratio is the
            // reference-font-size content width, independent of the live fontSizePx.
            val referenceContentW = contentW * (AsciiSettings.DEFAULT_FONT_SIZE_PX / geometry.fontSizePx)
            val fillScale = if (referenceContentW > 0f) size.width / referenceContentW else 1f

            val scaledW = contentW * fillScale
            val scaledH = contentH * fillScale
            val offsetX = (size.width - scaledW) / 2f
            val offsetY = (size.height - scaledH) / 2f

            val save = native.save()
            native.clipRect(0f, 0f, size.width, size.height)
            native.translate(offsetX, offsetY)
            native.scale(fillScale, fillScale)

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
