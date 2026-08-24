package com.llama.asciicam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llama.asciicam.pipeline.AsciiCanvas
import com.llama.asciicam.pipeline.MediaSource
import kotlinx.coroutines.launch

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
                viewModel.setPickedImage(bmp)
            }
        }
    }

    CameraHost(
        active = settings.mediaSource == MediaSource.CAMERA && hasCameraPermission,
        useFrontCamera = settings.useFrontCamera,
        viewModel = viewModel,
    )

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                    // First frame hasn't arrived yet.
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.align(Alignment.Center).padding(48.dp),
                    )
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
