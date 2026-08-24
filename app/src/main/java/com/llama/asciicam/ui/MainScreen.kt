package com.llama.asciicam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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

    var showSettings by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
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
                backgroundColor = Color.Black,
                modifier = Modifier.fillMaxSize(),
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
                ) {
                    ChromeIconButton(icon = Icons.Default.Settings, contentDescription = "Settings") {
                        showSettings = true
                    }
                    if (settings.mediaSource == MediaSource.CAMERA) {
                        ChromeIconButton(icon = Icons.Default.FlipCameraAndroid, contentDescription = "Switch camera") {
                            viewModel.updateSettings { it.copy(useFrontCamera = !it.useFrontCamera) }
                        }
                    }
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
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, start = 32.dp, end = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left slot: secondary capture action (plain-text export).
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
                        viewModel.exportPng(context, 1080, 1440) { ok ->
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

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = sheetState) {
            SettingsPanel(
                settings = settings,
                onSettingsChange = { transform -> viewModel.updateSettings(transform) },
                onPickImage = {
                    imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onExportPng = {
                    viewModel.exportPng(context, 1080, 1440) { ok ->
                        Toast.makeText(context, if (ok) "Saved PNG to Pictures/AsciiCam" else "Export failed", Toast.LENGTH_SHORT).show()
                    }
                },
                onExportTxt = {
                    viewModel.exportTxt(context) { ok ->
                        Toast.makeText(context, if (ok) "Saved TXT to Documents/AsciiCam" else "Export failed", Toast.LENGTH_SHORT).show()
                    }
                },
                onClose = { showSettings = false },
            )
        }
    }
}

@Composable
private fun ChromeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White)
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
    ) {
        modes.forEach { (source, label) ->
            val isSelected = source == selected
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(source) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.Yellow else Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
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
