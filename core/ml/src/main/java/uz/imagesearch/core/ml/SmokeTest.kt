package uz.imagesearch.core.ml

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

/**
 * ML diagnostic: loads all ONNX sessions, runs a dummy forward pass through each,
 * and reports load time, first/warm inference latency, I/O shapes, and peak RAM.
 *
 * Useful for verifying that assets copy correctly from APK, NNAPI doesn't crash
 * on a given device, and the total session footprint fits in the memory budget
 * (~280 MB estimated for all four models live simultaneously).
 */
object SmokeTest {

    private const val TAG = "MlSmokeTest"

    /** Models under test with their input sizes, matching the offline training config. */
    private val MODELS = listOf(
        ModelSpec("fashion_siglip", "models/fashion_siglip_int8.onnx", 224, 3, useNnapi = true,  keepAlive = true),
        ModelSpec("siglip2",         "models/siglip2_int8.onnx",        224, 3, useNnapi = true,  keepAlive = true),
        ModelSpec("dinov2s",         "models/dinov2s_int8.onnx",        224, 3, useNnapi = true,  keepAlive = true),
        // u2netp outputs a segmentation mask, not an embedding — we just verify it runs.
        // Testing three variants to find the fastest config on-device:
        //   320 + NNAPI (baseline), 256 + NNAPI (less compute), 320 + CPU-only
        ModelSpec("u2netp@320nnapi", "models/u2netp.onnx", 320, 3, useNnapi = true,  keepAlive = true),
        ModelSpec("u2netp@256nnapi", "models/u2netp.onnx", 256, 3, useNnapi = true,  keepAlive = false),
        ModelSpec("u2netp@320cpu",   "models/u2netp.onnx", 320, 3, useNnapi = false, keepAlive = false),
    )

    data class ModelSpec(
        val name: String,
        val asset: String,
        val inputSize: Int,
        val channels: Int,
        val useNnapi: Boolean,
        val keepAlive: Boolean,
    )

    data class ModelReport(
        val name: String,
        val ok: Boolean,
        val message: String,
        val loadMs: Long,
        val firstRunMs: Long,
        val warmRunMs: Long,
        val inputShape: List<Long>,
        val outputShape: List<Long>,
        val sizeMb: Long,
    )

    data class Report(
        val totalMs: Long,
        val peakHeapMb: Long,
        val peakNativeMb: Long,
        val models: List<ModelReport>,
    ) {
        fun pretty(): String = buildString {
            appendLine("ML smoke-test — total ${totalMs} ms")
            appendLine("Heap peak: ${peakHeapMb} MB · native peak: ${peakNativeMb} MB")
            appendLine()
            for (m in models) {
                val mark = if (m.ok) "OK " else "ERR"
                appendLine("[$mark] ${m.name}  (${m.sizeMb} MB)")
                if (m.ok) {
                    appendLine("      load=${m.loadMs} ms · 1st=${m.firstRunMs} ms · warm=${m.warmRunMs} ms")
                    appendLine("      in=${m.inputShape}  out=${m.outputShape}")
                } else {
                    appendLine("      ${m.message}")
                }
            }
        }
    }

    suspend fun run(context: Context): Report = withContext(Dispatchers.Default) {
        val env = OrtEnvironment.getEnvironment()
        val reports = mutableListOf<ModelReport>()
        var heapPeak = 0L
        var nativePeak = 0L

        val totalMs = measureTimeMillis {
            for (spec in MODELS) {
                val report = try {
                    runOne(context, env, spec)
                } catch (t: Throwable) {
                    Log.e(TAG, "Smoke-test failed for ${spec.name}", t)
                    ModelReport(
                        name = spec.name, ok = false,
                        message = "${t.javaClass.simpleName}: ${t.message}",
                        loadMs = -1, firstRunMs = -1, warmRunMs = -1,
                        inputShape = emptyList(), outputShape = emptyList(),
                        sizeMb = sizeMbOf(context, spec.asset),
                    )
                }
                reports += report
                heapPeak = maxOf(heapPeak, currentHeapMb())
                nativePeak = maxOf(nativePeak, currentNativeMb())
                // Keep all keepAlive sessions alive to measure peak RAM with all models loaded.
            }
        }

        Report(totalMs, heapPeak, nativePeak, reports).also {
            Log.i(TAG, "\n" + it.pretty())
        }
    }

    private fun runOne(context: Context, env: OrtEnvironment, spec: ModelSpec): ModelReport {
        val file = copyAsset(context, spec.asset)
        val sizeMb = file.length() / (1024L * 1024L)

        var session: OrtSession? = null
        val loadMs = measureTimeMillis {
            val opts = OrtSession.SessionOptions().apply {
                if (spec.useNnapi) {
                    try { addNnapi() } catch (t: Throwable) {
                        Log.w(TAG, "NNAPI not available for ${spec.name}: ${t.message}")
                    }
                }
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(file.absolutePath, opts)
        }
        val s = session!!

        val inputName = s.inputNames.first()
        val inputInfo = s.inputInfo[inputName]!!.info as TensorInfo
        // ONNX often declares dynamic dims as -1; substitute real values from ModelSpec.
        val declaredShape = inputInfo.shape.toList()
        val realShape = longArrayOf(1, spec.channels.toLong(), spec.inputSize.toLong(), spec.inputSize.toLong())

        val numel = realShape.fold(1L) { acc, x -> acc * x }.toInt()
        val buf = ByteBuffer.allocateDirect(numel * 4).order(ByteOrder.nativeOrder())
        // zeros input — timing only, output values are discarded.
        val tensor = OnnxTensor.createTensor(env, buf.asFloatBuffer(), realShape)

        val firstRunMs: Long
        val warmRunMs: Long
        var outputShape: List<Long> = emptyList()
        tensor.use {
            firstRunMs = measureTimeMillis {
                s.run(mapOf(inputName to it)).use { r ->
                    val info = r[0].info as TensorInfo
                    outputShape = info.shape.toList()
                    require(info.type == OnnxJavaType.FLOAT) {
                        "Unexpected output type for ${spec.name}: ${info.type}"
                    }
                }
            }
            warmRunMs = measureTimeMillis {
                s.run(mapOf(inputName to it)).use { /* discard */ }
            }
        }

        return ModelReport(
            name = spec.name, ok = true, message = "ok",
            loadMs = loadMs, firstRunMs = firstRunMs, warmRunMs = warmRunMs,
            inputShape = if (declaredShape.any { it < 0 }) realShape.toList() else declaredShape,
            outputShape = outputShape,
            sizeMb = sizeMb,
        ).also {
            if (!spec.keepAlive) {
                s.close()
                // GC after non-keepAlive variants so they don't inflate the native peak reading.
                System.gc()
            }
        }
    }

    private fun copyAsset(context: Context, assetPath: String): File {
        val out = File(context.filesDir, assetPath)
        // Re-copy if the asset size changed (e.g. after model re-quantization).
        val assetSize = try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Throwable) {
            // Compressed assets can't be opened via openFd; noCompress("onnx") avoids this.
            -1L
        }
        if (out.exists() && out.length() > 0 && (assetSize < 0 || out.length() == assetSize)) {
            return out
        }
        out.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun sizeMbOf(context: Context, asset: String): Long = try {
        context.assets.openFd(asset).use { it.length / (1024L * 1024L) }
    } catch (_: Throwable) {
        try {
            context.assets.open(asset).use { it.available().toLong() / (1024L * 1024L) }
        } catch (_: Throwable) { -1L }
    }

    private fun currentHeapMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
    }

    private fun currentNativeMb(): Long =
        Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
}
