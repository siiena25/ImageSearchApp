package uz.imagesearch.core.retrieval

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.min

/**
 * 1:1 port of color_v10.py (see /PycharmProjects/ImageSearch/color_v10.py).
 *
 * Any changes to formulas must be kept in sync with the Python side —
 * on-device color scores and offline catalog signatures must stay bit-compatible.
 */
object ColorSignatureV10 {

    const val HUE_BINS = 24
    const val SIGNATURE_VECTOR_LEN = 26

    private const val PRINT_SAT_THRESHOLD = 50
    private const val PRINT_V_MIN = 50
    private const val PRINT_V_MAX = 240
    private const val WHITE_THRESHOLD = 245

    /**
     * Threshold tuned for clean SAM2-cropped catalog images (white bg, no skin).
     * For segmented query images (u2netp crop), skin near the bbox edges can push
     * nPrint above 200 even on solid-color garments and cause false non-mono
     * classification — which zeroes out color similarity against mono catalog items.
     *
     * Using a combined criterion: monochrome if either the absolute print pixel
     * count is low (preserves catalog compatibility) OR the print ratio is under 5%.
     * This makes query classification robust to segmentation noise without touching
     * the catalog labeling.
     */
    private const val MIN_PRINT_PIXELS = 200
    private const val MONO_PRINT_RATIO = 0.05f

    private const val PEAK_DIST_KILL = 2
    private const val JACCARD_KILL_THRESHOLD = 0.30f
    private const val JACCARD_FULL_THRESHOLD = 0.40f
    private const val PEAK_SIM_DIVISOR = 2.5f

    // floor=0.30 rather than 0.05: items with a different palette now get a 0.30
    // multiplier instead of near-zero, so semantically strong matches can still
    // surface even when colors diverge (e.g. denim dress retrieved against jeans).
    // full=0.40 gives a smooth ramp rather than a hard gate.
    private const val COLOR_GATE_FLOOR = 0.30f
    private const val COLOR_GATE_FULL = 0.40f

    data class Signature(
        val hueHist: FloatArray,
        val monoRatio: Float,
        val isMonochrome: Boolean,
    )

    /** Extract signature from Bitmap (RGB → HSV pixel-by-pixel). */
    fun extract(bitmap: Bitmap): Signature {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = FloatArray(HUE_BINS)
        var nFg = 0
        var nPrint = 0
        val hsv = FloatArray(3)

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val notWhite = !(r >= WHITE_THRESHOLD && g >= WHITE_THRESHOLD && b >= WHITE_THRESHOLD)
            if (!notWhite) continue
            nFg++

            // PIL HSV: H,S,V ∈ [0,255]. Android Color.RGBToHSV: H∈[0,360], S∈[0,1], V∈[0,1].
            // Convert to PIL scale for bit-exact compatibility with Python signatures.
            Color.RGBToHSV(r, g, b, hsv)
            val hPil = ((hsv[0] / 360f) * 256f).toInt().coerceIn(0, 255)
            val sPil = (hsv[1] * 255f).toInt().coerceIn(0, 255)
            val vPil = (hsv[2] * 255f).toInt().coerceIn(0, 255)

            if (sPil >= PRINT_SAT_THRESHOLD && vPil in PRINT_V_MIN..PRINT_V_MAX) {
                nPrint++
                // np.histogram with range=(0,256), 24 bins → bin = hPil * 24 / 256
                val bin = (hPil.toLong() * HUE_BINS.toLong() / 256L).toInt()
                    .coerceIn(0, HUE_BINS - 1)
                hist[bin] += 1f
            }
        }

        val monoRatio = if (nFg > 0) 1f - nPrint.toFloat() / nFg else 1f
        val printRatio = if (nFg > 0) nPrint.toFloat() / nFg else 0f
        val isMonochrome = nPrint < MIN_PRINT_PIXELS || printRatio < MONO_PRINT_RATIO
        if (isMonochrome) hist.fill(0f)
        else {
            var sum = 0f
            for (v in hist) sum += v
            if (sum > 0f) for (i in hist.indices) hist[i] /= sum
        }
        return Signature(hist, monoRatio, isMonochrome)
    }

    fun toVector(s: Signature): FloatArray {
        val v = FloatArray(SIGNATURE_VECTOR_LEN)
        System.arraycopy(s.hueHist, 0, v, 0, HUE_BINS)
        v[HUE_BINS] = s.monoRatio
        v[HUE_BINS + 1] = if (s.isMonochrome) 1f else 0f
        return v
    }

    fun fromVector(v: FloatArray): Signature {
        val hist = FloatArray(HUE_BINS)
        System.arraycopy(v, 0, hist, 0, HUE_BINS)
        return Signature(hist, v[HUE_BINS], v[HUE_BINS + 1] > 0.5f)
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = if (a > b) a - b else b - a
        return min(d, HUE_BINS - d)
    }

    private fun weightedPeak(hist: FloatArray): Int {
        var bestIdx = 0
        var bestVal = -1f
        for (i in 0 until HUE_BINS) {
            val prev = hist[(i - 1 + HUE_BINS) % HUE_BINS]
            val next = hist[(i + 1) % HUE_BINS]
            val s = 0.25f * prev + 0.5f * hist[i] + 0.25f * next
            if (s > bestVal) { bestVal = s; bestIdx = i }
        }
        return bestIdx
    }

    private fun dominantBins(hist: FloatArray, coverage: Float = 0.70f, maxBins: Int = 6): Set<Int> {
        var sum = 0f
        for (v in hist) sum += v
        if (sum <= 0f) return emptySet()
        val order = (0 until HUE_BINS).sortedByDescending { hist[it] }
        var cum = 0f
        var n = 0
        for (idx in order) { cum += hist[idx]; n++; if (cum >= coverage) break }
        if (n > maxBins) n = maxBins
        if (n < 1) n = 1
        val out = HashSet<Int>()
        for (k in 0 until n) {
            val b = order[k]
            out.add(b)
            out.add((b - 1 + HUE_BINS) % HUE_BINS)
            out.add((b + 1) % HUE_BINS)
        }
        return out
    }

    /** [0, 1] color similarity (see color_v10.color_similarity). */
    fun similarity(a: Signature, b: Signature): Float {
        val bothMono = a.isMonochrome && b.isMonochrome
        val oneMono = a.isMonochrome != b.isMonochrome
        if (bothMono) return 0.7f
        if (oneMono) return 0f

        var hueInter = 0f
        for (i in 0 until HUE_BINS) hueInter += min(a.hueHist[i], b.hueHist[i])

        val peakA = weightedPeak(a.hueHist)
        val peakB = weightedPeak(b.hueHist)
        val peakDist = circularDistance(peakA, peakB)
        val peakSim = exp(-(peakDist * peakDist) / PEAK_SIM_DIVISOR)

        val domA = dominantBins(a.hueHist)
        val domB = dominantBins(b.hueHist)
        val jaccard = if (domA.isNotEmpty() && domB.isNotEmpty()) {
            val inter = domA.intersect(domB).size
            val union = (domA + domB).size
            if (union > 0) inter.toFloat() / union else 0f
        } else 0f

        if (peakDist > PEAK_DIST_KILL && jaccard < JACCARD_KILL_THRESHOLD) return 0f

        val hueMatch = 0.2f * hueInter + 0.3f * peakSim + 0.5f * jaccard
        val monoDiff = kotlin.math.abs(a.monoRatio - b.monoRatio)
        val monoMul = maxOf(exp(-((monoDiff * 2f) * (monoDiff * 2f))), 0.1f)
        val jaccGate = min(jaccard / JACCARD_FULL_THRESHOLD, 1f)
        return (hueMatch * monoMul * jaccGate).coerceIn(0f, 1f)
    }

    /** Final gate, applied after triple rerank. */
    fun gate(colScore: Float): Float =
        COLOR_GATE_FLOOR + (1f - COLOR_GATE_FLOOR) * min(colScore, COLOR_GATE_FULL) / COLOR_GATE_FULL
}
