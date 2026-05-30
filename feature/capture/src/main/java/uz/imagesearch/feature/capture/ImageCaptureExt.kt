package uz.imagesearch.feature.capture

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Takes a snapshot and saves it to `cacheDir/captures/<timestamp>.jpg`.
 * Returns a `file://` URI pointing to the saved JPEG.
 */
suspend fun ImageCapture.takeSnapshot(ctx: Context): Uri =
    suspendCancellableCoroutine { cont ->
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(file).build()

        takePicture(
            output,
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    cont.resume(result.savedUri ?: Uri.fromFile(file))
                }
                override fun onError(e: ImageCaptureException) {
                    cont.resumeWithException(e)
                }
            }
        )
    }

