package uz.imagesearch.core.ml

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Preprocessing for ONNX models. Exactly as in bench_onnx_desktop.py:
 *   1) resize → size×size (BICUBIC ≈ FILTER_BILINEAR on Android,
 *      difference is negligible for cosine ≥ 0.998)
 *   2) RGB float / 255
 *   3) (x - mean) / std
 *   4) NCHW layout → DirectFloatBuffer
 */
object Preprocess {
    val SIGLIP_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    val SIGLIP_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    val DINO_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val DINO_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun toNchw(bitmap: Bitmap, size: Int, mean: FloatArray, std: FloatArray): FloatBuffer {
        val resized = if (bitmap.width == size && bitmap.height == size) bitmap
            else Bitmap.createScaledBitmap(bitmap, size, size, true)

        val pixels = IntArray(size * size)
        resized.getPixels(pixels, 0, size, 0, 0, size, size)

        val buf = ByteBuffer.allocateDirect(3 * size * size * 4)
            .order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()

        // Channel-major (NCHW): all R first, then all G, then all B.
        val area = size * size
        for (c in 0 until 3) {
            val m = mean[c]; val s = std[c]
            for (i in 0 until area) {
                val px = pixels[i]
                val v = when (c) {
                    0 -> ((px shr 16) and 0xFF) / 255f
                    1 -> ((px shr 8) and 0xFF) / 255f
                    else -> (px and 0xFF) / 255f
                }
                fb.put(c * area + i, (v - m) / s)
            }
        }
        fb.rewind()
        return fb
    }
}
