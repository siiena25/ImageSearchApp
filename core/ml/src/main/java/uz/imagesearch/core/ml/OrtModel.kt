package uz.imagesearch.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Thin wrapper over ONNX Runtime. Each on-device model is one session.
 *
 * NB: ORT cannot read models from APK assets directly (for all ABI versions),
 * so on first launch we copy the .onnx file to filesDir and work from there.
 */
class OrtModel private constructor(
    private val env: OrtEnvironment,
    val session: OrtSession,
    val inputName: String,
) {
    fun encode(input: FloatBuffer, shape: LongArray): FloatArray {
        val tensor = OnnxTensor.createTensor(env, input, shape)
        return tensor.use {
            session.run(mapOf(inputName to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val arr = result[0].value as Array<FloatArray>
                normalize(arr[0])
            }
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s) + 1e-8f
        for (i in v.indices) v[i] = v[i] / n
        return v
    }

    fun close() { session.close() }

    companion object {
        fun fromAsset(context: Context, assetName: String): OrtModel {
            val env = OrtEnvironment.getEnvironment()
            val file = copyAssetToFiles(context, assetName)
            val opts = OrtSession.SessionOptions().apply {
                // On Pixel 10 / Android 16 try NNAPI; ORT will gracefully fall back
                // to CPU/XNNPACK if NNAPI does not support an op.
                try { addNnapi() } catch (_: Throwable) {}
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(file.absolutePath, opts)
            val inputName = session.inputNames.first()
            return OrtModel(env, session, inputName)
        }

        private fun copyAssetToFiles(context: Context, assetName: String): File {
            val out = File(context.filesDir, assetName.substringAfterLast('/'))
            if (out.exists() && out.length() > 0) return out
            out.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            return out
        }
    }
}
