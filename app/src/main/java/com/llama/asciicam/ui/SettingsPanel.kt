package com.llama.asciicam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llama.asciicam.pipeline.AsciiSettings
import com.llama.asciicam.pipeline.ColorMode
import com.llama.asciicam.pipeline.CharSource
import com.llama.asciicam.pipeline.DistortionType
import com.llama.asciicam.pipeline.FontChoice
import com.llama.asciicam.pipeline.MediaSource
import com.llama.asciicam.pipeline.NoiseType
import com.llama.asciicam.pipeline.PaletteStop

@Composable
fun SettingsPanel(
    settings: AsciiSettings,
    onSettingsChange: ((AsciiSettings) -> AsciiSettings) -> Unit,
    onPickImage: () -> Unit,
    onExportPng: () -> Unit,
    onExportTxt: () -> Unit,
    onClose: () -> Unit,
) {
    fun set(transform: (AsciiSettings) -> AsciiSettings) = onSettingsChange(transform)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
        }

        item { SectionLabel("Source") }
        item {
            ChoiceRow(
                options = listOf("Camera" to MediaSource.CAMERA, "Image" to MediaSource.IMAGE, "Noise" to MediaSource.NOISE),
                selected = settings.mediaSource,
                onSelect = { m -> set { it.copy(mediaSource = m) } },
            )
        }
        if (settings.mediaSource == MediaSource.CAMERA) {
            item {
                RowSwitch("Use front camera", settings.useFrontCamera) { v -> set { it.copy(useFrontCamera = v) } }
            }
        }
        if (settings.mediaSource == MediaSource.IMAGE) {
            item {
                Button(onClick = onPickImage, modifier = Modifier.padding(vertical = 8.dp)) { Text("Choose image from gallery") }
            }
        }
        if (settings.mediaSource == MediaSource.NOISE) {
            item {
                Dropdown(
                    label = "Noise type",
                    options = NoiseType.entries,
                    selected = settings.noiseType,
                    display = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                    onSelect = { v -> set { it.copy(noiseType = v) } },
                )
            }
            item { LabeledSlider("Scale", settings.noiseScale, 1f, 40f) { v -> set { it.copy(noiseScale = v) } } }
            item { LabeledSlider("Speed", settings.noiseSpeed, 0f, 5f) { v -> set { it.copy(noiseSpeed = v) } } }
            item { RowSwitch("Freeze", settings.noiseFrozen) { v -> set { it.copy(noiseFrozen = v) } } }
        }

        item { SectionLabel("Grid & font") }
        // Font size isn't a separate control: the grid always fills the screen
        // width for whatever "Columns" is set to (see AsciiPipeline.computeGridGeometry),
        // so Columns alone is both the density and the zoom control.
        item { LabeledSlider("Columns", settings.cols.toFloat(), 20f, 300f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(cols = v.toInt()) } } }
        item { LabeledSlider("Line spacing %", settings.lineSpacingPercent.toFloat(), 20f, 150f, valueLabel = { "${it.toInt()}%" }) { v -> set { it.copy(lineSpacingPercent = v.toInt()) } } }
        item { LabeledSlider("Char spacing %", settings.charSpacingPercent.toFloat(), 50f, 300f, valueLabel = { "${it.toInt()}%" }) { v -> set { it.copy(charSpacingPercent = v.toInt()) } } }
        item {
            Dropdown(
                label = "Font",
                options = FontChoice.entries,
                selected = settings.font,
                display = { it.displayName },
                onSelect = { v -> set { it.copy(font = v) } },
            )
        }

        item { SectionLabel("Character source") }
        item {
            ChoiceRow(
                options = listOf("Ramp" to CharSource.RAMP, "Word" to CharSource.WORD),
                selected = settings.charSource,
                onSelect = { v -> set { it.copy(charSource = v) } },
            )
        }
        if (settings.charSource == CharSource.RAMP) {
            item {
                OutlinedTextField(
                    value = settings.rampString,
                    onValueChange = { v -> set { it.copy(rampString = v) } },
                    label = { Text("Ramp string (dark -> light)") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
        } else {
            item {
                OutlinedTextField(
                    value = settings.wordString,
                    onValueChange = { v -> set { it.copy(wordString = v) } },
                    label = { Text("Word") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = settings.fillChars,
                    onValueChange = { v -> set { it.copy(fillChars = v) } },
                    label = { Text("Fill characters (dark -> light)") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            item { RowSwitch("Stable word (hold letters)", settings.stableWord) { v -> set { it.copy(stableWord = v) } } }
            if (settings.stableWord) {
                item {
                    LabeledSlider("Hold time (s)", settings.wordHoldTimeSeconds, 0.2f, 5.0f, valueLabel = { "%.1f".format(it) }) { v ->
                        set { it.copy(wordHoldTimeSeconds = v) }
                    }
                }
            }
        }

        item { SectionLabel("Edge detection") }
        item { RowSwitch("Detect edges", settings.edgeDetectEnabled) { v -> set { it.copy(edgeDetectEnabled = v) } } }
        if (settings.edgeDetectEnabled) {
            item { LabeledSlider("Edge threshold", settings.edgeThreshold.toFloat(), 0f, 100f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(edgeThreshold = v.toInt()) } } }
            item { LabeledSlider("Edge strength", settings.edgeStrength.toFloat(), 0f, 200f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(edgeStrength = v.toInt()) } } }
            item { RowSwitch("Solid edge color", settings.edgeColorEnabled) { v -> set { it.copy(edgeColorEnabled = v) } } }
            if (settings.edgeColorEnabled) {
                item {
                    ColorPickerRow(argb = settings.edgeColorArgb) { c -> set { it.copy(edgeColorArgb = c) } }
                }
            }
        }

        item { SectionLabel("Distortion") }
        item {
            Dropdown(
                label = "Type",
                options = DistortionType.entries,
                selected = settings.distortionType,
                display = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                onSelect = { v -> set { it.copy(distortionType = v) } },
            )
        }
        if (settings.distortionType != DistortionType.NONE) {
            item { LabeledSlider("Amount", settings.distortionAmount.toFloat(), 0f, 100f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(distortionAmount = v.toInt()) } } }
            item { LabeledSlider("Speed", settings.distortionSpeed.toFloat(), -300f, 300f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(distortionSpeed = v.toInt()) } } }
        }

        item { SectionLabel("Color adjust") }
        item { LabeledSlider("Brightness", settings.brightness.toFloat(), -100f, 100f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(brightness = v.toInt()) } } }
        item { LabeledSlider("Contrast", settings.contrast.toFloat(), -100f, 100f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(contrast = v.toInt()) } } }
        item { LabeledSlider("Exposure", settings.exposure.toFloat(), -100f, 100f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(exposure = v.toInt()) } } }
        item { LabeledSlider("Saturation", settings.saturation.toFloat(), 0f, 200f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(saturation = v.toInt()) } } }
        item { LabeledSlider("Gamma", settings.gamma.toFloat(), 20f, 300f, valueLabel = { it.toInt().toString() }) { v -> set { it.copy(gamma = v.toInt()) } } }
        item { RowSwitch("Invert ASCII", settings.invert) { v -> set { it.copy(invert = v) } } }

        item { SectionLabel("Color mode") }
        item {
            ChoiceRow(
                options = listOf("Source" to ColorMode.SOURCE, "Palette" to ColorMode.PALETTE, "Mono" to ColorMode.MONO),
                selected = settings.colorMode,
                onSelect = { v -> set { it.copy(colorMode = v) } },
            )
        }
        if (settings.colorMode == ColorMode.PALETTE) {
            item { PaletteEditor(settings.paletteStops) { stops -> set { it.copy(paletteStops = stops) } } }
        }

        item { SectionLabel("Block merge") }
        item { RowSwitch("Merge 3x3 blocks", settings.merge3x3) { v -> set { it.copy(merge3x3 = v) } } }
        item { RowSwitch("Merge 2x2 blocks", settings.merge2x2) { v -> set { it.copy(merge2x2 = v) } } }

        item { SectionLabel("Export") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = onExportPng) { Text("Save PNG") }
                OutlinedButton(onClick = onExportTxt) { Text("Save TXT") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Divider(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    valueLabel: (Float) -> String = { "%.2f".format(it) },
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

// Stacked full-width, not a horizontal row: the settings panel is only half
// the screen wide, and 3+ pill buttons side by side there don't have room to
// stay on one line — Button/OutlinedButton don't truncate their label, so a
// squeezed one wraps into a near-circular blob instead. Every option gets a
// full-width row of its own here, and both selected/unselected states share
// the same shape (ButtonDefaults.shape) so only the fill color differs.
@Composable
private fun <T> ChoiceRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        options.forEach { (label, value) ->
            if (value == selected) {
                Button(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}

@Composable
private fun <T> Dropdown(label: String, options: List<T>, selected: T, display: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(display(selected))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(display(opt)) }, onClick = { onSelect(opt); expanded = false })
                }
            }
        }
    }
}

private val PRESET_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFF34C759.toInt(),
    0xFF5B8CFF.toInt(), 0xFFFFCC00.toInt(), 0xFFFF2D95.toInt(), 0xFF00E5FF.toInt(),
)

@Composable
private fun ColorPickerRow(argb: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESET_COLORS.forEach { c ->
                val swatchColor = androidx.compose.ui.graphics.Color(c)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(swatchColor, CircleShape)
                        .then(
                            if (c == argb) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            else Modifier,
                        )
                        .clickable { onChange(c) },
                )
            }
        }
        var hex by remember(argb) { mutableStateOf(String.format("#%06X", argb and 0xFFFFFF)) }
        OutlinedTextField(
            value = hex,
            onValueChange = { v ->
                hex = v
                val parsed = com.llama.asciicam.pipeline.AsciiPipeline.parseHexColor(v)
                onChange(parsed)
            },
            label = { Text("Hex") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun PaletteEditor(stops: List<PaletteStop>, onChange: (List<PaletteStop>) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("Palette stops (low -> high brightness)", style = MaterialTheme.typography.bodyMedium)
        stops.forEachIndexed { index, stop ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                OutlinedTextField(
                    value = stop.hex,
                    onValueChange = { v ->
                        val newStops = stops.toMutableList()
                        newStops[index] = PaletteStop(v)
                        onChange(newStops)
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Stop ${index + 1}") },
                )
                IconButton(onClick = {
                    if (stops.size > 2) onChange(stops.toMutableList().also { it.removeAt(index) })
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove stop")
                }
            }
        }
        OutlinedButton(onClick = { onChange(stops + PaletteStop("#FFFFFF")) }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add stop")
        }
    }
}
