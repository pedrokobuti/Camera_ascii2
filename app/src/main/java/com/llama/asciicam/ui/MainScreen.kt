package com.llama.asciicam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llama.asciicam.pipeline.AsciiCanvas
import com.llama.asciicam.pipeline.MediaSource
import kotlinx.coroutines.launch

/** Explicit white-on-black scheme for the settings panel — the panel's
 * background is unconditionally black, so its text/icon colors shouldn't
 * follow the system light/dark scheme (whose "light" variant is dark-on-light
 * and would be unreadable here). */
private val SettingsPanelColors = darkColorScheme(
    primary = Color(0xFF5B8CFF),
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color.White,
    onPrimary = Color.White,
)

/**
 * Full-bleed viewfinder with stock-camera-app chrome: translucent top/bottom
 * scrims holding small icon controls, a mode-selector row (Camera/Image/
 * Noise, standing in for Photo/Video mode switchers), and a bottom bar with
 * a circular shutter button that "captures" the current ASCII frame as a PNG.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: AsciiViewModel = viewModel()) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var requestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        requestedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val settings = viewModel.settings
    val renderState = viewModel.render
    val frame = renderState?.frame
    val geometry = renderState?.geometry
    val isRecording = viewModel.isRecording

    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bmp = loadBitmapFromUri(context, uri)
                viewModel.onImagePicked(bmp)
            }
        }
    }

    CameraHost(
        active = settings.mediaSource == MediaSource.CAMERA && hasCameraPermission,
        useFrontCamera = settings.useFrontCamera,
        viewModel = viewModel,
    )

    val shutterFlash = remember { Animatable(0f) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (settings.mediaSource == MediaSource.CAMERA && !hasCameraPermission) {
            PermissionScreen(
                rationaleNeeded = !requestedOnce,
                permanentlyDenied = requestedOnce,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { openAppSettings(context) },
            )
        } else {
            AsciiCanvas(
                frame = frame,
                geometry = geometry,
                font = settings.font,
                // Invert ASCII flips which brightness maps to which glyph density
                // (handled in the pipeline), and pairs that with a white instead of
                // black background — glyph color itself is unaffected either way.
                backgroundColor = if (settings.invert) Color.White else Color.Black,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewModel.reportViewportSize(it.width, it.height) }
                    // Pinch-to-zoom over the viewfinder, as in the stock camera
                    // app. Only meaningful while the camera is the source — the
                    // image and noise sources have no sensor to zoom. Keyed on
                    // mediaSource so the gesture detector is dropped entirely
                    // rather than sitting there swallowing pinches for a source
                    // that can't act on them.
                    .pointerInput(settings.mediaSource) {
                        if (settings.mediaSource != MediaSource.CAMERA) return@pointerInput
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            viewModel.onPinchZoom(gestureZoom)
                        }
                    },
            )
            if (frame == null) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.align(Alignment.Center).size(64.dp),
                )
            }

            // Capture-flash feedback, same beat as a stock camera shutter.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = shutterFlash.value)),
            )

            // Top scrim: settings (left) + camera flip (right, camera mode only).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Gear + wordmark as one target, so the label is part of the
                    // control rather than decoration sitting next to it.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            ) { showSettings = true }
                            .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Menu",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text("MENU", style = Hud.Label, color = Color.White)
                    }
                    if (isRecording) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .border(1.dp, Hud.Danger, NotchedShape(5.dp))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Hud.Danger),
                            )
                            Text(
                                "REC",
                                style = Hud.Readout,
                                color = Hud.Danger,
                                modifier = Modifier.padding(start = 7.dp),
                            )
                        }
                    }
                    // No camera-flip control here: the bottom bar's right slot
                    // already carries one in camera mode, and two buttons doing
                    // the same thing on one screen is just noise. The spacer
                    // holds the right end of the SpaceBetween row so the REC
                    // badge stays where it was rather than sliding over.
                    Spacer(Modifier.width(48.dp))
                }
            }

            // Bottom scrim: mode selector + shutter row, mirroring a stock camera app.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                    .padding(WindowInsets.navigationBars.asPaddingValues())
                    .padding(bottom = 16.dp, top = 24.dp),
            ) {
                ModeSelectorRow(
                    selected = settings.mediaSource,
                    onSelect = { source -> viewModel.updateSettings { it.copy(mediaSource = source) } },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, start = 24.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Video record toggle.
                    ChromeIconButton(
                        icon = if (isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                        contentDescription = if (isRecording) "Stop recording" else "Record video",
                        tint = if (isRecording) Color.Red else Color.White,
                    ) {
                        if (isRecording) {
                            viewModel.stopRecording { ok, fps ->
                                Toast.makeText(
                                    context,
                                    if (ok) "Saved to Movies/AsciiCam · %.1f fps".format(fps) else "Recording failed",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } else {
                            viewModel.startRecording(context) { ok, diagnostic ->
                                // Always shown, not just on failure: `diagnostic`
                                // carries the build marker, the encoder's real
                                // output size and whether the font loaded — the
                                // on-device evidence for which build is running.
                                Toast.makeText(
                                    context,
                                    if (ok) "REC $diagnostic" else "Couldn't start recording · $diagnostic",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }

                    // Secondary capture action (plain-text export).
                    ChromeIconButton(icon = Icons.Default.Description, contentDescription = "Save as text") {
                        viewModel.exportTxt(context) { ok ->
                            Toast.makeText(context, if (ok) "Saved TXT to Documents/AsciiCam" else "Export failed", Toast.LENGTH_SHORT).show()
                        }
                    }

                    ShutterButton {
                        scope.launch {
                            shutterFlash.snapTo(0.85f)
                            shutterFlash.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(220))
                        }
                        viewModel.exportPng(context) { ok ->
                            Toast.makeText(context, if (ok) "Saved PNG to Pictures/AsciiCam" else "Export failed", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // Right slot: source-dependent secondary action.
                    when (settings.mediaSource) {
                        MediaSource.IMAGE -> ChromeIconButton(icon = Icons.Default.PhotoLibrary, contentDescription = "Pick image") {
                            imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        MediaSource.CAMERA -> ChromeIconButton(icon = Icons.Default.FlipCameraAndroid, contentDescription = "Switch camera") {
                            viewModel.updateSettings { it.copy(useFrontCamera = !it.useFrontCamera) }
                        }
                        MediaSource.NOISE -> Box(modifier = Modifier.size(48.dp))
                    }
                }
            }
        }
    }

    // Side panel instead of a full-width bottom sheet, so the viewfinder stays
    // visible (and live) behind it while adjusting settings.
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { showSettings = false },
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            // Force a dark scheme AND use Surface (not a plain Box) here: Surface
            // is what actually sets the default text/icon color for its content
            // via LocalContentColor — a Box with a background modifier does not,
            // which is why text was rendering in its unset default (black) on
            // this panel's black background.
            MaterialTheme(colorScheme = SettingsPanelColors) {
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        // Wider than the previous half-screen: the HUD layout
                        // puts a framed panel around each section, and at 50%
                        // the labelled slider rows had no room to breathe.
                        .fillMaxWidth(0.66f),
                    color = Hud.Bg,
                    contentColor = Hud.TextPrimary,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Hud.LineDim),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(WindowInsets.statusBars.asPaddingValues())
                            .padding(WindowInsets.navigationBars.asPaddingValues()),
                    ) {
                        SettingsPanel(
                        settings = settings,
                        onSettingsChange = { transform -> viewModel.updateSettings(transform) },
                        onPickImage = {
                            imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onExportPng = {
                            viewModel.exportPng(context) { ok ->
                                Toast.makeText(context, if (ok) "Saved PNG to Pictures/AsciiCam" else "Export failed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onExportTxt = {
                            viewModel.exportTxt(context) { ok ->
                                Toast.makeText(context, if (ok) "Saved TXT to Documents/AsciiCam" else "Export failed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onClose = { showSettings = false },
                        canUndo = viewModel.canUndo,
                        canRedo = viewModel.canRedo,
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                        onReset = { viewModel.resetToDefaults() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

/** Stock-camera-style shutter: a white ring with a solid center disc. */
@Composable
private fun ShutterButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun ModeSelectorRow(selected: MediaSource, onSelect: (MediaSource) -> Unit) {
    val modes = listOf(
        MediaSource.NOISE to "NOISE",
        MediaSource.CAMERA to "CAMERA",
        MediaSource.IMAGE to "IMAGE",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { (source, label) ->
            val isSelected = source == selected
            // Selected mode gets a bracketed readout rather than a pill, to
            // match the settings sheet's instrument styling.
            Row(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { onSelect(source) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelected) {
                    Text("[", style = Hud.Label, color = Hud.TextFaint)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = label,
                    style = Hud.Label,
                    color = if (isSelected) Hud.Accent else Hud.TextFaint,
                )
                if (isSelected) {
                    Spacer(Modifier.width(5.dp))
                    Text("]", style = Hud.Label, color = Hud.TextFaint)
                }
            }
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

private suspend fun loadBitmapFromUri(context: android.content.Context, uri: Uri): android.graphics.Bitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            null
        }
    }
