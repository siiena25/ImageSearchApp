package uz.imagesearch.feature.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Holds CameraX use-case references and UI-observable camera state (torch, zoom, lens).
 * The state is consumed by [CaptureScreen] via Compose state observation.
 */
class CameraState(
    val previewView: PreviewView,
) {
    var imageCapture: ImageCapture? = null
        internal set
    var cameraControl: androidx.camera.core.CameraControl? = null
        internal set
    var lensFacing: Int by androidx.compose.runtime.mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    var torchOn: Boolean by mutableStateOf(false)
    var maxZoom: Float by androidx.compose.runtime.mutableFloatStateOf(1f)
    var zoomRatio: Float by androidx.compose.runtime.mutableFloatStateOf(1f)

    fun toggleLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        // Front camera usually has no torch — reset to keep UI consistent.
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) torchOn = false
    }

    fun toggleTorch() {
        if (lensFacing != CameraSelector.LENS_FACING_BACK) return
        val next = !torchOn
        cameraControl?.enableTorch(next)
        torchOn = next
    }

    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(1f, maxZoom.coerceAtLeast(1f))
        cameraControl?.setZoomRatio(clamped)
        zoomRatio = clamped
    }
}

@Composable
fun rememberCameraState(): CameraState {
    val ctx = LocalContext.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }
    return remember { CameraState(previewView) }
}

/**
 * Binds CameraX Preview + ImageCapture use-cases to the screen lifecycle.
 * Rebinds automatically when [CameraState.lensFacing] changes.
 */
@Composable
fun CameraPreview(
    state: CameraState,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(state.lensFacing) {
        val provider = awaitCameraProvider(ctx)
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = state.previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val selector = CameraSelector.Builder().requireLensFacing(state.lensFacing).build()

        val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        state.imageCapture = capture
        state.cameraControl = camera.cameraControl
        state.maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        state.zoomRatio = 1f
    }

    DisposableEffect(Unit) {
        onDispose {
            ProcessCameraProvider.getInstance(ctx).get().unbindAll()
        }
    }

    AndroidView(factory = { state.previewView }, modifier = modifier)
}

private suspend fun awaitCameraProvider(ctx: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(ctx))
    }
