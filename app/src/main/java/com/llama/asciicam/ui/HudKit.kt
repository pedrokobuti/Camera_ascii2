package com.llama.asciicam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A small HUD/telemetry-style widget kit: hairline strokes on black, notched
 * corners, wide-tracked uppercase labels and bracketed numeric readouts.
 *
 * These replace the stock Material controls throughout the settings panel. The
 * behaviour is deliberately identical to what they replace — this is styling
 * and grouping only, so nothing here changes what a control does, just how it
 * reads.
 */
object Hud {
    val Bg = Color(0xFF07080A)
    val PanelBg = Color(0xFF0B0D10)
    val Line = Color(0xFF6E767F)
    val LineBright = Color(0xFFD6DCE2)
    val LineDim = Color(0xFF272C32)
    val TextPrimary = Color(0xFFE9EDF1)
    val TextDim = Color(0xFF8A929B)
    val TextFaint = Color(0xFF5A626B)
    val Accent = Color(0xFFFFFFFF)
    val Danger = Color(0xFFFF6E58)

    /** Uppercase, wide-tracked technical label — the sheet's base voice. */
    val Label = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 1.6.sp,
        fontWeight = FontWeight.Medium,
    )
    val LabelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 2.4.sp,
        fontWeight = FontWeight.Medium,
    )
    val Readout = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.Bold,
    )
    val Title = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 20.sp,
        letterSpacing = 6.sp,
        fontWeight = FontWeight.Light,
    )
}

/**
 * Rectangle with corners cut at 45° — the panel/button silhouette the whole
 * reference sheet is built from. Each corner is switchable, so segmented
 * controls can notch only their outer edges and butt together in the middle.
 */
class NotchedShape(
    private val cut: Dp,
    private val topStart: Boolean = true,
    private val topEnd: Boolean = true,
    private val bottomEnd: Boolean = true,
    private val bottomStart: Boolean = true,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val p = Path().apply {
            moveTo(if (topStart) c else 0f, 0f)
            lineTo(if (topEnd) size.width - c else size.width, 0f)
            if (topEnd) lineTo(size.width, c)
            lineTo(size.width, if (bottomEnd) size.height - c else size.height)
            if (bottomEnd) lineTo(size.width - c, size.height)
            lineTo(if (bottomStart) c else 0f, size.height)
            if (bottomStart) lineTo(0f, size.height - c)
            lineTo(0f, if (topStart) c else 0f)
            close()
        }
        return Outline.Generic(p)
    }
}

/** Numbered section rule: `01 // SOURCE ───────────┐`. */
@Composable
fun HudSectionHeader(index: Int, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Hud.TextFaint)) { append("%02d".format(index)) }
                withStyle(SpanStyle(color = Hud.TextFaint)) { append("  //  ") }
                withStyle(SpanStyle(color = Hud.LineBright)) { append(title.uppercase(Locale.US)) }
            },
            style = Hud.LabelLarge,
        )
        Spacer(Modifier.width(10.dp))
        // Rule with a downward tick at its end, echoing the reference's frames.
        Canvas(Modifier.weight(1f).height(8.dp)) {
            val y = size.height / 2f
            drawLine(Hud.LineDim, Offset(0f, y), Offset(size.width - 6f, y), strokeWidth = 1f)
            drawLine(Hud.LineDim, Offset(size.width - 6f, y), Offset(size.width - 6f, size.height), strokeWidth = 1f)
        }
    }
}

/** Framed container with notched corners — groups one section's controls. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = remember { NotchedShape(8.dp) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Hud.PanelBg)
            .border(1.dp, Hud.LineDim, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { content() }
}

/**
 * Slider drawn as a segmented telemetry track. Hand-drawn rather than a
 * restyled Material Slider so the tick marks, filled run and block thumb are
 * exactly the reference's, and so it doesn't depend on which Material3 slot
 * API this Compose version ships.
 *
 * The readout doubles as a text field — tap the number to type an exact value,
 * which is then clamped to `min..max`.
 */
@Composable
fun HudSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    valueLabel: (Float) -> String = { "%.2f".format(Locale.US, it) },
    onChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label.uppercase(Locale.US), style = Hud.Label, color = Hud.TextDim)
            HudValueReadout(value = value, min = min, max = max, valueLabel = valueLabel, onChange = onChange)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .trackDragInput { f -> onChange(min + f * (max - min)) },
        ) {
            val midY = size.height / 2f
            val tickCount = 40
            val step = size.width / tickCount
            // Unfilled ticks across the whole run, brighter over the filled part.
            for (i in 0..tickCount) {
                val x = i * step
                val on = x <= size.width * fraction
                val h = if (i % 5 == 0) size.height * 0.42f else size.height * 0.22f
                drawLine(
                    color = if (on) Hud.LineBright else Hud.LineDim,
                    start = Offset(x, midY - h / 2f),
                    end = Offset(x, midY + h / 2f),
                    strokeWidth = 1.2f,
                )
            }
            drawLine(Hud.LineDim, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
            drawLine(Hud.LineBright, Offset(0f, midY), Offset(size.width * fraction, midY), strokeWidth = 1.6f)
            // Block thumb, kept fully inside the track at both extremes.
            val tw = with(density) { 3.dp.toPx() }
            val th = size.height * 0.8f
            val tx = (size.width * fraction).coerceIn(tw / 2f, size.width - tw / 2f)
            drawRect(
                color = Hud.Accent,
                topLeft = Offset(tx - tw / 2f, midY - th / 2f),
                size = Size(tw, th),
            )
        }
    }
}

/** Bracketed `[ value ]` readout that becomes a text field when tapped. */
@Composable
private fun HudValueReadout(
    value: Float,
    min: Float,
    max: Float,
    valueLabel: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    // onFocusChanged fires once with isFocused=false as the field attaches,
    // before focus is requested; without this latch that first callback would
    // close the editor before a character could be typed.
    var hasTakenFocus by remember { mutableStateOf(false) }

    fun commit() {
        if (!editing) return
        editing = false
        val parsed = draft.trim().replace(',', '.').toFloatOrNull()
        if (parsed != null && parsed.isFinite()) onChange(parsed.coerceIn(min, max))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("[", style = Hud.Readout, color = Hud.TextFaint)
        Spacer(Modifier.width(4.dp))
        if (editing) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = Hud.Readout.copy(color = Hud.Accent, textAlign = TextAlign.End),
                cursorBrush = SolidColor(Hud.Accent),
                keyboardOptions = KeyboardOptions(
                    // A number pad has no minus key on most Android keyboards,
                    // so ranges that go negative use the text keyboard instead.
                    keyboardType = if (min < 0f) KeyboardType.Text else KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .width(64.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) hasTakenFocus = true
                        else if (hasTakenFocus) commit()
                    },
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            DisposableEffect(Unit) { onDispose { commit() } }
        } else {
            Text(
                valueLabel(value),
                style = Hud.Readout,
                color = Hud.Accent,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    draft = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
                    hasTakenFocus = false
                    editing = true
                },
            )
        }
        Spacer(Modifier.width(4.dp))
        Text("]", style = Hud.Readout, color = Hud.TextFaint)
    }
}

/**
 * Tap-to-jump plus drag-to-scrub on a horizontal track, reporting position as
 * a 0..1 fraction of the track's width.
 *
 * One gesture loop rather than a `detectTapGestures` + `detectHorizontalDrag`
 * pair: stacked detectors on the same element race for the same pointer, and
 * the tap detector's press handler can swallow the down event the drag
 * detector needs. Taking the down here and then dragging from it makes both
 * behaviours come from the same stream. Width comes from the pointer scope's
 * own `size`, which is always the laid-out size — not from a value captured
 * during a draw pass, which wouldn't exist yet on a first touch.
 */
private fun Modifier.trackDragInput(onFraction: (Float) -> Unit): Modifier =
    pointerInput(Unit) {
        val width = { size.width.toFloat().coerceAtLeast(1f) }
        awaitEachGesture {
            val down = awaitFirstDown()
            onFraction((down.position.x / width()).coerceIn(0f, 1f))
            down.consume()
            drag(down.id) { change ->
                onFraction((change.position.x / width()).coerceIn(0f, 1f))
                change.consume()
            }
        }
    }

/** On/off control drawn as a bracketed state word rather than a Material switch. */
@Composable
fun HudToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onChange(!checked) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(Locale.US), style = Hud.Label, color = Hud.TextDim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudCheckbox(checked)
            Spacer(Modifier.width(9.dp))
            Text(
                if (checked) "ON" else "OFF",
                style = Hud.Readout,
                color = if (checked) Hud.Accent else Hud.TextFaint,
            )
        }
    }
}

/**
 * Square check control: an outlined box that fills and takes a drawn tick when
 * on. Paired with the ON/OFF readout so state is legible two ways — the mark
 * carries it at a glance, the word removes any doubt.
 */
@Composable
fun HudCheckbox(checked: Boolean) {
    Canvas(Modifier.size(14.dp)) {
        val inset = 1f
        val box = Size(size.width - inset * 2, size.height - inset * 2)
        if (checked) {
            drawRect(Hud.Accent, topLeft = Offset(inset, inset), size = box)
            // Tick drawn in the negative space of the filled box.
            val w = size.width
            val h = size.height
            val stroke = w * 0.13f
            drawLine(
                color = Color.Black,
                start = Offset(w * 0.24f, h * 0.52f),
                end = Offset(w * 0.43f, h * 0.72f),
                strokeWidth = stroke,
            )
            drawLine(
                color = Color.Black,
                start = Offset(w * 0.43f, h * 0.72f),
                end = Offset(w * 0.77f, h * 0.29f),
                strokeWidth = stroke,
            )
        } else {
            drawRect(Hud.Line, topLeft = Offset(inset, inset), size = box, style = Stroke(width = 1.3f))
        }
    }
}

/** Horizontal segmented selector — selected segment inverts to solid. */
@Composable
fun <T> HudSegmented(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        options.forEachIndexed { i, (label, value) ->
            val isSelected = value == selected
            val shape = remember(i, options.size) {
                NotchedShape(
                    cut = 6.dp,
                    topStart = i == 0,
                    bottomStart = i == 0,
                    topEnd = i == options.lastIndex,
                    bottomEnd = i == options.lastIndex,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(shape)
                    .background(if (isSelected) Hud.Accent else Color.Transparent)
                    .border(1.dp, if (isSelected) Hud.Accent else Hud.LineDim, shape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(Locale.US),
                    style = Hud.Label,
                    color = if (isSelected) Color.Black else Hud.TextDim,
                )
            }
            if (i != options.lastIndex) Spacer(Modifier.width(4.dp))
        }
    }
}

/** Full-width action button with notched corners. */
@Composable
fun HudButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = remember { NotchedShape(7.dp) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(if (emphasized) Hud.Accent else Color.Transparent)
            .border(1.dp, if (emphasized) Hud.Accent else Hud.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(Locale.US),
            style = Hud.Label,
            color = if (emphasized) Color.Black else Hud.TextPrimary,
        )
    }
}

/** Labelled dropdown styled as a readout field with a caret glyph. */
@Composable
fun <T> HudDropdown(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = remember { NotchedShape(6.dp) }
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label.uppercase(Locale.US), style = Hud.Label, color = Hud.TextDim)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(shape)
                    .border(1.dp, Hud.LineDim, shape)
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(display(selected).uppercase(Locale.US), style = Hud.Readout, color = Hud.TextPrimary)
                Text("▼", style = Hud.Label, color = Hud.TextFaint)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Hud.PanelBg),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                display(option).uppercase(Locale.US),
                                style = Hud.Readout,
                                color = if (option == selected) Hud.Accent else Hud.TextDim,
                            )
                        },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

/** Single-line text input styled to match the readouts. */
@Composable
fun HudTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val shape = remember { NotchedShape(6.dp) }
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label.uppercase(Locale.US), style = Hud.Label, color = Hud.TextDim)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(shape)
                .border(1.dp, Hud.LineDim, shape)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Hud.Readout.copy(color = Hud.TextPrimary),
                cursorBrush = SolidColor(Hud.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Small dim caption used for hints under a control. */
@Composable
fun HudCaption(text: String) {
    Text(
        text.uppercase(Locale.US),
        style = Hud.Label.copy(fontSize = 9.sp),
        color = Hud.TextFaint,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
    )
}

/** Formats a float the way the reference sheet renders telemetry: zero-padded. */
fun hudInt(value: Float, digits: Int = 3): String =
    value.roundToInt().let {
        val s = kotlin.math.abs(it).toString().padStart(digits, '0')
        if (it < 0) "-$s" else s
    }
