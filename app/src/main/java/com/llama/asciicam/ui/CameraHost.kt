package com.llama.asciicam.ui

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.llama.asciicam.pipeline.CameraFrameAnalyzer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors

/**
 * Binds a CameraX [ImageAnalysis] use case (no raw preview — the ASCII canvas
 * *is* the viewfinder) whenever [active] is true, and unbinds it otherwise
 * (e.g. while the image or noise source is selected, or permission is
 * missing). Rebinds automatically when [useFrontCamera] toggles.
 *
 * The camera provider is fetched exactly once (via [LaunchedEffect]) and the
 * analyzer executor is created exactly once for this composable's lifetime
 * (via [remember]) and shut down only when it finally leaves composition.
 * An earlier version recreated both the executor *and* the provider fetch on
 * every single active/useFrontCamera change, inside a DisposableEffect keyed
 * on those same values — tearing down and shutting down the executor
 * immediately on each toggle raced against that toggle's own in-flight
 * (asynchronous) provider-fetch listener from a moment earlier: if a stale
 * listener fired after its executor had already been shut down, CameraX
 * would try to dispatch a camera frame to a dead executor
 * (RejectedExecutionException, uncaught on a CameraX-internal thread —
 * exactly the kind of crash reported when tapping the flip-camera button).
 */
@Composable
fun CameraHost(
    active: Boolean,
    useFrontCamera: Boolean,
    viewModel: AsciiViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        provider = try {
            suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    { cont.resumeWith(runCatching { future.get() }) },
                    ContextCompat.getMainExecutor(context),
                )
            }
        } catch (e: Exception) {
            Log.e("CameraHost", "Failed to obtain ProcessCameraProvider", e)
            null
        }
    }

    DisposableEffect(active, useFrontCamera, provider) {
        val p = provider
        if (active && p != null) {
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(
                executor,
                CameraFrameAnalyzer(
                    cols = { viewModel.currentGridCols() },
                    rows = { viewModel.currentGridRows() },
                    mirror = { useFrontCamera },
                    targetAspect = { viewModel.currentViewportAspect() },
                ) { r, g, b, cols, rows, srcW, srcH ->
                    viewModel.onCameraFrame(r, g, b, cols, rows, srcW, srcH)
                },
            )
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, analysis)
            } catch (e: Exception) {
                // Camera unavailable (e.g. no front camera on this device/emulator, or in-use elsewhere).
                Log.e("CameraHost", "Failed to bind camera (front=$useFrontCamera)", e)
            }
        } else {
            p?.unbindAll()
        }

        onDispose {
            p?.unbindAll()
        }
    }
}
