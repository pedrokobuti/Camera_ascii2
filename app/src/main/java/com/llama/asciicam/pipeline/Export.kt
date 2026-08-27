package com.llama.asciicam.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export helpers: render the current [AsciiFrameResult] to a PNG bitmap
 * (mirrors the original tool's PNG snapshot export), a raw row-major
 * character grid (mirrors its .txt export), or record it live to an MP4
 * (see [VideoRecorder], which reuses [drawFrameInto] below).
 */
object Export {

    private fun timestampName(ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "asciicam_$ts.$ext"
    }

    /**
     * Draws one frame onto an arbitrary [Canvas] at [outWidth]x[outHeight] —
     * shared by [renderToBitmap] (a Bitmap-backed Canvas) and [VideoRecorder]
     * (a video encoder's input Surface's locked Canvas), so a recorded frame
     * looks exactly like a PNG snapshot of the same moment. [paint] and
     * [baselineRatio] are precomputed by the caller (typeface loading and
     * glyph-metrics measurement aren't free — a video recorder calls this
     * once per frame and shouldn't redo them every time).
     */
    internal fun drawFrameInto(
        canvas: Canvas,
        frame: AsciiFrameResult,
        geometry: GridGeometry,
        paint: Paint,
        baselineRatio: Float,
        outWidth: Int,
        outHeight: Int,
        backgroundArgb: Int,
    ) {
        canvas.drawColor(backgroundArgb)

        val contentW = geometry.cols * geometry.cellW
        val contentH = geometry.rows * geometry.rowPitch
        if (contentW <= 0f || contentH <= 0f) return
        val scale = minOf(outWidth / contentW, outHeight / contentH)
        val offsetX = (outWidth - contentW * scale) / 2f
        val offsetY = (outHeight - contentH * scale) / 2f

        val save = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        val cols = geometry.cols
        val rows = geometry.rows
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val idx = y * cols + x
                val span = frame.span[idx]
                if (span <= 0) continue
                val ch = frame.chars[idx]
                if (ch == ' ') continue
                val fontSize = geometry.fontSizePx * span
                paint.textSize = fontSize
                paint.color = frame.colors[idx]
                val cx = (x + span / 2f) * geometry.cellW
                val cy = (y + span / 2f) * geometry.rowPitch
                canvas.drawText(ch.toString(), cx, cy - baselineRatio * fontSize, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    /** Renders the frame into a standalone bitmap at [outWidth]x[outHeight] pixels. */
    fun renderToBitmap(
        context: Context,
        frame: AsciiFrameResult,
        geometry: GridGeometry,
        font: FontChoice,
        backgroundArgb: Int,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val typeface = GlyphMetrics.typefaceFor(context, font)
        val baselineRatio = GlyphMetrics.measureBaselineOffsetRatio(typeface)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
        }
        drawFrameInto(canvas, frame, geometry, paint, baselineRatio, outWidth, outHeight, backgroundArgb)
        return bmp
    }

    /** Saves [bitmap] as a PNG into MediaStore Pictures/AsciiCam. Returns true on success. */
    fun savePng(context: Context, bitmap: Bitmap): Boolean {
        val name = timestampName("png")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AsciiCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Writes the raw row-major character grid as plain text into MediaStore Documents/AsciiCam. */
    fun saveTxt(context: Context, frame: AsciiFrameResult): Boolean {
        val sb = StringBuilder(frame.cols * frame.rows + frame.rows)
        for (y in 0 until frame.rows) {
            for (x in 0 until frame.cols) {
                val idx = y * frame.cols + x
                sb.append(if (frame.span[idx] <= 0) ' ' else frame.chars[idx])
            }
            sb.append('\n')
        }
        val name = timestampName("txt")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/AsciiCam")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Files.getContentUri("external")
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
