package uz.imagesearch.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Query segmentation via U²-Netp (320×320 fixed input, CPU EP only).
 *
 * NNAPI is intentionally avoided here: on Pixel 10 it runs ConvTranspose2d
 * layers in CPU fallback anyway and ends up ~7.8× slower than running
 * the full graph on CPU directly (2543 ms vs 327 ms).
 *
 * Pipeline: resize input to 320×320 → normalize (mean=0.485, std=0.229,
 * same single-channel scheme as the original u2netp repo) → ORT inference →
 * min-max normalize raw scores → threshold at 0.5 → compute tight bbox →
 * scale bbox back to original resolution → white-fill outside mask →
 * square-crop with padding → resize to [outSize].
 *
 * The resulting crop matches the catalog preparation (SAM2 crops on white bg),
 * which is important for embedding parity.
 */
class Segmenter(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
) {

    /** Segment the subject and return a square white-background crop ready for encoding. */
    fun segmentToWhiteBg(src: Bitmap, outSize: Int = 256): Bitmap {
        val (mask, bbox) = runMask(src) ?: return centerSquare(src, outSize)
        return composeOnWhite(src, mask, bbox, outSize)
    }

    /** Run U²-Netp, normalize output scores, return mask + tight bbox in original coordinates. */
    private fun runMask(src: Bitmap): Pair<FloatArray, Rect>? {
        val nchw = preprocess(src)
        val tensor = OnnxTensor.createTensor(env, nchw, longArrayOf(1, 3, IN_SIZE.toLong(), IN_SIZE.toLong()))
        val mask320 = tensor.use {
            session.run(mapOf(inputName to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<Array<FloatArray>>> // [1][1][320][320]
                val flat = FloatArray(IN_SIZE * IN_SIZE)
                val plane = out[0][0]
                for (y in 0 until IN_SIZE) {
                    System.arraycopy(plane[y], 0, flat, y * IN_SIZE, IN_SIZE)
                }
                flat
            }
        }
        // U²-Netp outputs raw logit-like scores; min-max normalize to [0,1] as rembg does.
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in mask320) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = (hi - lo).coerceAtLeast(1e-6f)
        for (i in mask320.indices) mask320[i] = (mask320[i] - lo) / span

        var x0 = IN_SIZE; var y0 = IN_SIZE; var x1 = -1; var y1 = -1
        for (y in 0 until IN_SIZE) {
            val rowOff = y * IN_SIZE
            for (x in 0 until IN_SIZE) {
                if (mask320[rowOff + x] >= 0.5f) {
                    if (x < x0) x0 = x
                    if (y < y0) y0 = y
                    if (x > x1) x1 = x
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return null // empty mask

        val sx = src.width.toFloat() / IN_SIZE
        val sy = src.height.toFloat() / IN_SIZE
        val bbox = Rect(
            (x0 * sx).toInt().coerceAtLeast(0),
            (y0 * sy).toInt().coerceAtLeast(0),
            ((x1 + 1) * sx).toInt().coerceAtMost(src.width),
            ((y1 + 1) * sy).toInt().coerceAtMost(src.height),
        )
        return mask320 to bbox
    }

    /**
     * Alpha-blend the source image against white using the 320×320 mask,
     * crop to the subject bbox, pad to square, and scale to [outSize].
     */
    private fun composeOnWhite(src: Bitmap, mask320: FloatArray, bbox: Rect, outSize: Int): Bitmap {
        val masked = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val mPixels = IntArray(src.width * src.height)
        src.getPixels(mPixels, 0, src.width, 0, 0, src.width, src.height)
        val sx = IN_SIZE.toFloat() / src.width
        val sy = IN_SIZE.toFloat() / src.height
        for (y in 0 until src.height) {
            val my = (y * sy).toInt().coerceIn(0, IN_SIZE - 1)
            val rowOff = my * IN_SIZE
            val outRow = y * src.width
            for (x in 0 until src.width) {
                val mx = (x * sx).toInt().coerceIn(0, IN_SIZE - 1)
                val a = mask320[rowOff + mx]
                if (a <= 0.05f) {
                    mPixels[outRow + x] = Color.WHITE
                } else if (a < 0.95f) {
                    val px = mPixels[outRow + x]
                    val r = ((px shr 16) and 0xFF) * a + 255f * (1f - a)
                    val g = ((px shr 8) and 0xFF) * a + 255f * (1f - a)
                    val b = (px and 0xFF) * a + 255f * (1f - a)
                    mPixels[outRow + x] = Color.rgb(r.toInt(), g.toInt(), b.toInt())
                }
            }
        }
        masked.setPixels(mPixels, 0, src.width, 0, 0, src.width, src.height)

        val cropW = bbox.width()
        val cropH = bbox.height()
        val side = maxOf(cropW, cropH)
        val out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val scale = outSize.toFloat() / side
        val drawW = cropW * scale
        val drawH = cropH * scale
        val dx = (outSize - drawW) / 2f
        val dy = (outSize - drawH) / 2f
        val dst = RectF(dx, dy, dx + drawW, dy + drawH)
        canvas.drawBitmap(masked, bbox, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        masked.recycle()
        return out
    }

    /** Fallback when the mask is empty — return a center-square crop. */
    private fun centerSquare(src: Bitmap, outSize: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val out = Bitmap.createScaledBitmap(cropped, outSize, outSize, true)
        if (cropped !== out) cropped.recycle()
        return out
    }

    private fun preprocess(src: Bitmap): java.nio.FloatBuffer {
        val resized = if (src.width == IN_SIZE && src.height == IN_SIZE) src
            else Bitmap.createScaledBitmap(src, IN_SIZE, IN_SIZE, true)
        val pixels = IntArray(IN_SIZE * IN_SIZE)
        resized.getPixels(pixels, 0, IN_SIZE, 0, 0, IN_SIZE, IN_SIZE)
        if (resized !== src) resized.recycle()

        val buf = ByteBuffer.allocateDirect(3 * IN_SIZE * IN_SIZE * 4).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        val area = IN_SIZE * IN_SIZE
        // Single-channel mean/std as in the original u2net code
        val mean = 0.485f; val std = 0.229f
        for (c in 0 until 3) {
            for (i in 0 until area) {
                val px = pixels[i]
                val v = when (c) {
                    0 -> ((px shr 16) and 0xFF) / 255f
                    1 -> ((px shr 8) and 0xFF) / 255f
                    else -> (px and 0xFF) / 255f
                }
                fb.put(c * area + i, (v - mean) / std)
            }
        }
        fb.rewind()
        return fb
    }

    fun close() = session.close()

    companion object {
        private const val IN_SIZE = 320

        fun load(env: OrtEnvironment, modelFile: File): Segmenter {
            val opts = OrtSession.SessionOptions().apply {
                // NOT calling addNnapi() — CPU is significantly faster for this model.
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = env.createSession(modelFile.absolutePath, opts)
            return Segmenter(env, s, s.inputNames.first())
        }
    }
}
