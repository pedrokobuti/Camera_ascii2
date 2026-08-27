package com.llama.asciicam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.llama.asciicam.pipeline.AsciiPipeline
import com.llama.asciicam.pipeline.AsciiSettings
import com.llama.asciicam.pipeline.CharSource
import com.llama.asciicam.pipeline.ColorMode
import com.llama.asciicam.pipeline.DistortionType
import com.llama.asciicam.pipeline.EdgeColorMode
import com.llama.asciicam.pipeline.FontChoice
import com.llama.asciicam.pipeline.MediaSource
import com.llama.asciicam.pipeline.NoiseType
import com.llama.asciicam.pipeline.PaletteStop
import java.util.Locale

/**
 * The settings sheet, styled as a technical HUD readout (see [HudKit] for the
 * widget kit). Every section is a numbered, framed block so the sheet reads as
 * a set of distinct instrument panels rather than one long undifferentiated
 * list of Material controls.
 *
 * Only presentation changed here — each control drives exactly the same
 * setting, through the same `set { ... }` calls, as before.
 */
@Composable
fun SettingsPanel(
    settings: AsciiSettings,
    onSettingsChange: ((AsciiSettings) -> AsciiSettings) -> Unit,
    onPickImage: () -> Unit,
    onExportPng: () -> Unit,
    onExportTxt: () -> Unit,
    onClose: () -> Unit,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onReset: () -> Unit = {},
) {
    fun set(transform: (AsciiSettings) -> AsciiSettings) = onSettingsChange(transform)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hud.Bg)
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item { PanelMasthead(settings, onClose) }
        item {
            HistoryBar(
                canUndo = canUndo,
                canRedo = canRedo,
                onUndo = onUndo,
                onRedo = onRedo,
                onReset = onReset,
            )
        }

        // ---- 01 source ----
        item { HudSectionHeader(1, "Source") }
        item {
            HudPanel {
                Column {
                    HudSegmented(
                        options = listOf("Camera" to MediaSource.CAMERA, "Image" to MediaSource.IMAGE, "Noise" to MediaSource.NOISE),
                        selected = settings.mediaSource,
                        onSelect = { m -> set { it.copy(mediaSource = m) } },
                    )
                    when (settings.mediaSource) {
                        MediaSource.CAMERA -> {
                            HudToggle("Front camera", settings.useFrontCamera) { v -> set { it.copy(useFrontCamera = v) } }
                            HudCaption("Pinch the viewfinder to zoom")
                        }
                        MediaSource.IMAGE -> {
                            Spacer(Modifier.height(8.dp))
                            HudButton("Choose image", onClick = onPickImage)
                        }
                        MediaSource.NOISE -> {
                            HudDropdown(
                                label = "Noise type",
                                options = NoiseType.entries,
                                selected = settings.noiseType,
                                display = { it.name.titleCase() },
                                onSelect = { v -> set { it.copy(noiseType = v) } },
                            )
                            HudSlider("Scale", settings.noiseScale, 1f, 40f, valueLabel = { "%.1f".format(Locale.US, it) }) { v -> set { it.copy(noiseScale = v) } }
                            HudSlider("Speed", settings.noiseSpeed, 0f, 5f, valueLabel = { "%.2f".format(Locale.US, it) }) { v -> set { it.copy(noiseSpeed = v) } }
                            HudToggle("Freeze", settings.noiseFrozen) { v -> set { it.copy(noiseFrozen = v) } }
                        }
                    }
                }
            }
        }

        // ---- 02 grid & font ----
        item { HudSectionHeader(2, "Grid & Font") }
        item {
            HudPanel {
                Column {
                    // Font size isn't a separate control: the grid always fills the
                    // screen width for whatever Columns is set to (see
                    // AsciiPipeline.computeGridGeometry), so Columns alone is both
                    // the density and the zoom control.
                    HudSlider("Columns", settings.cols.toFloat(), 20f, 120f, valueLabel = { hudInt(it) }) { v -> set { it.copy(cols = v.toInt()) } }
                    HudSlider("Line spacing", settings.lineSpacingPercent.toFloat(), 20f, 150f, valueLabel = { "${hudInt(it)}%" }) { v -> set { it.copy(lineSpacingPercent = v.toInt()) } }
                    HudSlider("Char spacing", settings.charSpacingPercent.toFloat(), 50f, 300f, valueLabel = { "${hudInt(it)}%" }) { v -> set { it.copy(charSpacingPercent = v.toInt()) } }
                    HudDropdown(
                        label = "Typeface",
                        options = FontChoice.entries,
                        selected = settings.font,
                        display = { it.displayName },
                        onSelect = { v -> set { it.copy(font = v) } },
                    )
                    HudCaption("Glyph size auto-fits the cell per font")
                }
            }
        }

        // ---- 03 character source ----
        item { HudSectionHeader(3, "Characters") }
        item {
            HudPanel {
                Column {
                    HudSegmented(
                        options = listOf("Ramp" to CharSource.RAMP, "Word" to CharSource.WORD),
                        selected = settings.charSource,
                        onSelect = { v -> set { it.copy(charSource = v) } },
                    )
                    if (settings.charSource == CharSource.RAMP) {
                        HudTextField("Ramp  dark → light", settings.rampString) { v -> set { it.copy(rampString = v) } }
                    } else {
                        HudTextField("Word", settings.wordString) { v -> set { it.copy(wordString = v) } }
                        HudTextField("Fill  dark → light", settings.fillChars) { v -> set { it.copy(fillChars = v) } }
                        HudToggle("Hold letters", settings.stableWord) { v -> set { it.copy(stableWord = v) } }
                        if (settings.stableWord) {
                            HudSlider("Hold time", settings.wordHoldTimeSeconds, 0.2f, 5.0f, valueLabel = { "%.1fs".format(Locale.US, it) }) { v ->
                                set { it.copy(wordHoldTimeSeconds = v) }
                            }
                        }
                    }
                }
            }
        }

        // ---- 04 edges ----
        item { HudSectionHeader(4, "Edge Detect") }
        item {
            HudPanel {
                Column {
                    HudToggle("Detect edges", settings.edgeDetectEnabled) { v -> set { it.copy(edgeDetectEnabled = v) } }
                    if (settings.edgeDetectEnabled) {
                        HudSlider("Threshold", settings.edgeThreshold.toFloat(), 0f, 100f, valueLabel = { hudInt(it) }) { v -> set { it.copy(edgeThreshold = v.toInt()) } }
                        HudSlider("Strength", settings.edgeStrength.toFloat(), 0f, 200f, valueLabel = { hudInt(it) }) { v -> set { it.copy(edgeStrength = v.toInt()) } }
                        HudRule()
                        HudCaption("Outline color")
                        HudSegmented(
                            options = listOf("Off" to EdgeColorMode.OFF, "Custom" to EdgeColorMode.CUSTOM, "1mposter" to EdgeColorMode.IMPOSTER),
                            selected = settings.edgeColorMode,
                            onSelect = { v -> set { it.copy(edgeColorMode = v) } },
                        )
                        if (settings.edgeColorMode == EdgeColorMode.CUSTOM) {
                            ColorPickerRow(argb = settings.edgeColorArgb) { c -> set { it.copy(edgeColorArgb = c) } }
                        }
                    }
                }
            }
        }

        // ---- 05 distortion ----
        item { HudSectionHeader(5, "Distortion") }
        item {
            HudPanel {
                Column {
                    HudDropdown(
                        label = "Type",
                        options = DistortionType.entries,
                        selected = settings.distortionType,
                        display = { it.name.titleCase() },
                        onSelect = { v -> set { it.copy(distortionType = v) } },
                    )
                    if (settings.distortionType != DistortionType.NONE) {
                        HudSlider("Amount", settings.distortionAmount.toFloat(), 0f, 100f, valueLabel = { hudInt(it) }) { v -> set { it.copy(distortionAmount = v.toInt()) } }
                        HudSlider("Speed", settings.distortionSpeed.toFloat(), -300f, 300f, valueLabel = { hudInt(it) }) { v -> set { it.copy(distortionSpeed = v.toInt()) } }
                    }
                }
            }
        }

        // ---- 06 color adjust ----
        item { HudSectionHeader(6, "Signal") }
        item {
            HudPanel {
                Column {
                    HudSlider("Brightness", settings.brightness.toFloat(), -100f, 100f, valueLabel = { hudInt(it) }) { v -> set { it.copy(brightness = v.toInt()) } }
                    HudSlider("Contrast", settings.contrast.toFloat(), -100f, 100f, valueLabel = { hudInt(it) }) { v -> set { it.copy(contrast = v.toInt()) } }
                    HudSlider("Exposure", settings.exposure.toFloat(), -100f, 100f, valueLabel = { hudInt(it) }) { v -> set { it.copy(exposure = v.toInt()) } }
                    HudSlider("Saturation", settings.saturation.toFloat(), 0f, 200f, valueLabel = { hudInt(it) }) { v -> set { it.copy(saturation = v.toInt()) } }
                    HudSlider("Gamma", settings.gamma.toFloat(), 20f, 300f, valueLabel = { hudInt(it) }) { v -> set { it.copy(gamma = v.toInt()) } }
                    HudRule()
                    HudToggle("Invert ASCII", settings.invert) { v -> set { it.copy(invert = v) } }
                }
            }
        }

        // ---- 07 color mode ----
        item { HudSectionHeader(7, "Palette") }
        item {
            HudPanel {
                Column {
                    HudSegmented(
                        options = listOf("Source" to ColorMode.SOURCE, "Palette" to ColorMode.PALETTE),
                        selected = settings.colorMode,
                        onSelect = { v -> set { it.copy(colorMode = v) } },
                    )
                    Spacer(Modifier.height(4.dp))
                    HudSegmented(
                        options = listOf("1mposter" to ColorMode.IMPOSTER, "Mono" to ColorMode.MONO),
                        selected = settings.colorMode,
                        onSelect = { v -> set { it.copy(colorMode = v) } },
                    )
                    if (settings.colorMode == ColorMode.PALETTE) {
                        HudRule()
                        PaletteEditor(settings.paletteStops) { stops -> set { it.copy(paletteStops = stops) } }
                    }
                }
            }
        }

        // ---- 08 block merge ----
        item { HudSectionHeader(8, "Block Merge") }
        item {
            HudPanel {
                Column {
                    HudToggle("Merge 3×3", settings.merge3x3) { v -> set { it.copy(merge3x3 = v) } }
                    HudToggle("Merge 2×2", settings.merge2x2) { v -> set { it.copy(merge2x2 = v) } }
                    HudCaption("Flat areas collapse into larger glyphs")
                }
            }
        }

        // ---- 09 export ----
        item { HudSectionHeader(9, "Export") }
        item {
            HudPanel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { HudButton("Save PNG", onClick = onExportPng) }
                    Box(Modifier.weight(1f)) { HudButton("Save TXT", onClick = onExportTxt) }
                }
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            Text(
                "— END —",
                style = Hud.Label,
                color = Hud.TextFaint,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Title block: product mark, live grid readout, close control. */
@Composable
private fun PanelMasthead(settings: AsciiSettings, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ASCII CAM", style = Hud.Title, color = Hud.LineBright)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(1.dp, Hud.Line, NotchedShape(6.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", style = Hud.Readout, color = Hud.TextPrimary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Hud.TextFaint)) { append("CONTROL SURFACE  //  ") }
                withStyle(SpanStyle(color = Hud.TextDim)) { append("COLS ${hudInt(settings.cols.toFloat())}") }
                withStyle(SpanStyle(color = Hud.TextFaint)) { append("  ·  ") }
                withStyle(SpanStyle(color = Hud.TextDim)) { append(settings.font.displayName.uppercase(Locale.US)) }
            },
            style = Hud.Label,
        )
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(Hud.Line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
        }
    }
}

/**
 * Undo / redo / reset row, directly under the masthead.
 *
 * Undo and redo disable themselves when their stack is empty rather than
 * disappearing, so the row's layout doesn't shift as history changes.
 */
@Composable
private fun HistoryBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudIconAction(
            icon = Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
            enabled = canUndo,
            onClick = onUndo,
        )
        HudIconAction(
            icon = Icons.AutoMirrored.Filled.Redo,
            contentDescription = "Redo",
            enabled = canRedo,
            onClick = onRedo,
        )
        Box(Modifier.weight(1f)) {
            HudButton("Reset to defaults", onClick = onReset)
        }
    }
}

/** Square outlined icon button matching [HudButton]'s silhouette. */
@Composable
private fun HudIconAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = remember { NotchedShape(7.dp) }
    val tint = if (enabled) Hud.TextPrimary else Hud.TextFaint
    Box(
        modifier = Modifier
            .size(38.dp)
            .border(1.dp, if (enabled) Hud.Line else Hud.LineDim, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Hairline separator inside a panel. */
@Composable
private fun HudRule() {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(9.dp)
            .padding(vertical = 4.dp),
    ) {
        drawLine(Hud.LineDim, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1f)
    }
}

private fun String.titleCase(): String =
    lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) }

private val PRESET_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF6E58.toInt(), 0xFF34C759.toInt(),
    0xFF5B8CFF.toInt(), 0xFFFFCC00.toInt(), 0xFFFF2D95.toInt(), 0xFF00E5FF.toInt(),
)

@Composable
private fun ColorPickerRow(argb: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESET_COLORS.forEach { c ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(c), CircleShape)
                        .then(
                            if (c == argb) Modifier.border(2.dp, Hud.Accent, CircleShape)
                            else Modifier.border(1.dp, Hud.LineDim, CircleShape),
                        )
                        .clickable { onChange(c) },
                )
            }
        }
        var hex by remember(argb) { mutableStateOf(String.format(Locale.US, "#%06X", argb and 0xFFFFFF)) }
        HudTextField("Hex", hex) { v ->
            hex = v
            onChange(AsciiPipeline.parseHexColor(v))
        }
    }
}

@Composable
private fun PaletteEditor(stops: List<PaletteStop>, onChange: (List<PaletteStop>) -> Unit) {
    Column {
        HudCaption("Stops  low → high brightness")
        stops.forEachIndexed { index, stop ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(Color(AsciiPipeline.parseHexColor(stop.hex)), CircleShape)
                        .border(1.dp, Hud.LineDim, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    HudTextField("Stop ${index + 1}", stop.hex) { v ->
                        onChange(stops.toMutableList().also { it[index] = PaletteStop(v) })
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(1.dp, if (stops.size > 2) Hud.Line else Hud.LineDim, NotchedShape(5.dp))
                        .clickable(enabled = stops.size > 2) {
                            onChange(stops.toMutableList().also { it.removeAt(index) })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("−", style = Hud.Readout, color = if (stops.size > 2) Hud.TextPrimary else Hud.TextFaint)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HudButton("+ Add stop") { onChange(stops + PaletteStop("#FFFFFF")) }
    }
}
