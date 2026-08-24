package com.llama.asciicam.ui

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.llama.asciicam.pipeline.CameraFrameAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Binds a CameraX [ImageAnalysis] use case (no raw preview — the ASCII canvas
 * *is* the viewfinder) whenever [active] is true, and unbinds it otherwise
 * (e.g. while the image or noise source is selected, or permission is
 * missing). Rebinds automatically when [useFrontCamera] toggles.
 */
@Composable
fun CameraHost(
    active: Boolean,
    useFrontCamera: Boolean,
    viewModel: AsciiViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(active, useFrontCamera) {
        var executor: ExecutorService? = null
        var providerRef: ProcessCameraProvider? = null

        if (active) {
            executor = Executors.newSingleThreadExecutor()
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                val provider = providerFuture.get()
                providerRef = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(
                    executor!!,
                    CameraFrameAnalyzer(
                        cols = { viewModel.currentGridCols() },
                        rows = { viewModel.currentGridRows() },
                        mirror = { useFrontCamera },
                    ) { r, g, b, cols, rows, srcW, srcH ->
                        viewModel.onCameraFrame(r, g, b, cols, rows, srcW, srcH)
                    },
                )
                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, analysis)
                } catch (_: Exception) {
                    // Camera unavailable (e.g. emulator without a camera, or in-use elsewhere).
                }
            }, contextExecutorOf(context))
        }

        onDispose {
            providerRef?.unbindAll()
            executor?.shutdown()
        }
    }
}

private fun contextExecutorOf(context: Context) = androidx.core.content.ContextCompat.getMainExecutor(context)
