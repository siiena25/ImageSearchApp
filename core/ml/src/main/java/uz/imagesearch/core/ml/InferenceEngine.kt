package uz.imagesearch.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uz.imagesearch.core.catalog.BundleLoader
import uz.imagesearch.core.catalog.LoadedBundle
import uz.imagesearch.core.retrieval.ColorSignatureV10
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * On-device inference pipeline:
 *
 *   bitmap → U²-Netp segmentation → 256×256 white-bg crop
 *            ↓
 *   raw image → FashionSigLIP → visual-prototype router → category
 *   crop      → FashionSigLIP (or SigLIP2 for watches) → query embedding
 *   crop      → DINOv2 → texture embedding
 *   crop      → ColorSignatureV10 → color signature
 *            ↓
 *   CosineRetriever → top-10 results
 *
 * Sessions are initialized lazily on the first [get] call and kept alive
 * for the process lifetime — subsequent queries reuse the warmed-up sessions.
 * All [runQuery] calls are serialized through a Mutex (single CPU/NNAPI resource).
 */
class InferenceEngine private constructor(
    private val env: OrtEnvironment,
    private val segmenter: Segmenter,
    private val siglip2: OrtSession,
    private val fashion: OrtSession,
    private val dinov2: OrtSession,
    val bundle: LoadedBundle,
) {
    private val mutex = Mutex()

    /** query results plus per-stage latency breakdown. */
    data class QueryOutput(
        val category: String,
        val results: List<uz.imagesearch.core.retrieval.RetrievalResult>,
        val latencyMs: LatencyBreakdown,
    )

    data class LatencyBreakdown(
        val segmentMs: Long,
        val routeMs: Long,
        val encodeMs: Long,
        val textureMs: Long,
        val colorMs: Long,
        val retrievalMs: Long,
    ) {
        val total: Long get() = segmentMs + routeMs + encodeMs + textureMs + colorMs + retrievalMs
    }

    /**
     * Router decision: which encoder to use and which categories to search.
     *
     * `primary` determines the encoder (SigLIP2 for watches, FashionSigLIP otherwise).
     * `candidates` is the retrieval pool — normally just `{primary}`, but expands to
     * top-2 categories when the router margin is below [LOW_CONFIDENCE_MARGIN].
     * Watches are never mixed into fashion union pools (different embedding spaces).
     */
    data class RouterDecision(
        val primary: String,
        val candidates: Set<String>,
        val sims: Map<String, Float>,
        val margin: Float,
    )

    suspend fun runQuery(src: Bitmap, topK: Int = 10): QueryOutput = mutex.withLock {
        withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()

            // Route on the raw image rather than the segmented crop: segmentation
            // noise (especially on difficult shots) shifts the embedding off-distribution
            // and confuses the router. Raw embeddings give more stable category margins.
            //
            // Text-prompt routing (SigLIP2 + category names) had narrow cross-category
            // cosine margins (~0.03–0.04 between dresses/tshirts/jeans) causing frequent
            // misrouting. Visual prototypes (mean FashionSigLIP per category) push the
            // margin to ~0.12 for fashion and ~0.27 for watches.
            val rawClip = Preprocess.toNchw(src, 224, Preprocess.SIGLIP_MEAN, Preprocess.SIGLIP_STD)
            val fashRaw = encode(fashion, rawClip, 768)
            val decision = routeByVisualPrototypes(fashRaw)
            val tRoute = System.nanoTime()

            val cropped = segmenter.segmentToWhiteBg(src, outSize = 256)
            val tSeg = System.nanoTime()

            val qEmb = if (decision.primary == WATCHES_CATEGORY) {
                val cropClip = Preprocess.toNchw(cropped, 224, Preprocess.SIGLIP_MEAN, Preprocess.SIGLIP_STD)
                encode(siglip2, cropClip, 768)
            } else {
                val cropClip = Preprocess.toNchw(cropped, 224, Preprocess.SIGLIP_MEAN, Preprocess.SIGLIP_STD)
                encode(fashion, cropClip, 768)
            }
            val tEnc = System.nanoTime()

            val xDino = Preprocess.toNchw(cropped, 224, Preprocess.DINO_MEAN, Preprocess.DINO_STD)
            val qTex = encode(dinov2, xDino, 384)
            val tTex = System.nanoTime()

            val qColor = ColorSignatureV10.extract(cropped)
            val tCol = System.nanoTime()

            // In union retrieval the primary category gets a small adaptive boost to
            // compensate for catalog size imbalance (e.g. 399 dresses vs 66 jeans).
            // Boost scales with router margin: higher confidence → stronger bias toward
            // the predicted category. At margin=0 (fully uncertain) no boost is applied.
            val primaryBoost = if (decision.candidates.size > 1) {
                1.0f + (decision.margin / LOW_CONFIDENCE_MARGIN).coerceIn(0f, 1f) * MAX_PRIMARY_BOOST
            } else 1.0f
            val retriever = uz.imagesearch.core.retrieval.CosineRetriever(bundle)
            val results = retriever.retrieve(
                queryEmbedding = qEmb,
                queryTexture = qTex,
                queryColor = qColor,
                categories = decision.candidates,
                topK = topK,
                primaryCategory = decision.primary,
                primaryBoost = primaryBoost,
            )
            val tRet = System.nanoTime()

            cropped.recycle()

            QueryOutput(
                category = if (decision.candidates.size == 1) decision.primary
                           else "${decision.primary} (+ ${(decision.candidates - decision.primary).joinToString()})",
                results = results,
                latencyMs = LatencyBreakdown(
                    segmentMs   = (tSeg - tRoute) / 1_000_000,
                    routeMs     = (tRoute - t0) / 1_000_000,
                    encodeMs    = (tEnc - tSeg) / 1_000_000,
                    textureMs   = (tTex - tEnc) / 1_000_000,
                    colorMs     = (tCol - tTex) / 1_000_000,
                    retrievalMs = (tRet - tCol) / 1_000_000,
                ),
            ).also {
                Log.i(TAG, "router decision: ${decision.sims} → primary=${decision.primary} " +
                    "candidates=${decision.candidates} margin=${decision.margin} " +
                    "boost=$primaryBoost, top=${results.size}, latency=${it.latencyMs}")
            }
        }
    }

    private fun encode(session: OrtSession, input: java.nio.FloatBuffer, dim: Int): FloatArray {
        val name = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 224, 224))
        return tensor.use {
            session.run(mapOf(name to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val arr = result[0].value as Array<FloatArray>
                normalize(arr[0])
            }
        }
    }

    /**
     * Category router using per-category visual prototypes (mean FashionSigLIP
     * embeddings over the catalog). Falls back to text-embedding routing for
     * bundle v1 which predates the prototype file.
     *
     * When the best–second-best cosine margin is below [LOW_CONFIDENCE_MARGIN],
     * both top-2 categories are returned as candidates for union retrieval —
     * the semantic encoder then resolves the ambiguity naturally.
     *
     * Watches are excluded from union pools: they use a different encoder
     * (SigLIP2) and their embeddings live in an incompatible space.
     */
    private fun routeByVisualPrototypes(qVec: FloatArray): RouterDecision {
        val protos = bundle.categoryVisualPrototypes
            ?: return legacyTextDecision(qVec)

        val cats = bundle.manifest.routerCategories
        val dim = bundle.manifest.embeddingDim
        val row = FloatArray(dim)
        val sims = FloatArray(cats.size)
        synchronized(protos) {
            for (i in cats.indices) {
                protos.position(i * dim)
                protos.get(row, 0, dim)
                var s = 0f
                for (k in 0 until dim) s += row[k] * qVec[k]
                sims[i] = s
            }
        }
        // argmax + 2nd-max for margin
        var best = 0; var second = 1
        if (sims[1] > sims[0]) { best = 1; second = 0 }
        for (i in 2 until sims.size) {
            when {
                sims[i] > sims[best] -> { second = best; best = i }
                sims[i] > sims[second] -> second = i
            }
        }
        val primary = cats[best]
        val secondary = cats[second]
        val margin = sims[best] - sims[second]

        // Union only if both candidates are in the fashion encoder space.
        val candidates = if (margin < LOW_CONFIDENCE_MARGIN
            && primary != WATCHES_CATEGORY
            && secondary != WATCHES_CATEGORY
        ) setOf(primary, secondary) else setOf(primary)

        return RouterDecision(
            primary = primary,
            candidates = candidates,
            sims = cats.zip(sims.toList()).toMap(),
            margin = margin,
        )
    }

    /** Text-embedding router — used when the bundle lacks visual prototypes (v1). */
    private fun legacyTextDecision(qVec: FloatArray): RouterDecision {
        val cats = bundle.manifest.routerCategories
        val dim = bundle.manifest.embeddingDim
        val row = FloatArray(dim)
        val sims = FloatArray(cats.size)
        synchronized(bundle.categoryText) {
            for (i in cats.indices) {
                bundle.categoryText.position(i * dim)
                bundle.categoryText.get(row, 0, dim)
                var s = 0f
                for (k in 0 until dim) s += row[k] * qVec[k]
                sims[i] = s
            }
        }
        var bestIdx = 0
        for (i in 1 until sims.size) if (sims[i] > sims[bestIdx]) bestIdx = i
        return RouterDecision(
            primary = cats[bestIdx],
            candidates = setOf(cats[bestIdx]),
            sims = cats.zip(sims.toList()).toMap(),
            margin = 0f,
        )
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s) + 1e-8f
        for (i in v.indices) v[i] = v[i] / n
        return v
    }

    fun close() {
        segmenter.close()
        siglip2.close()
        fashion.close()
        dinov2.close()
    }

    companion object {
        private const val TAG = "InferenceEngine"
        private const val WATCHES_CATEGORY = "watches"

        /**
         * Router margin threshold below which union retrieval is triggered.
         *
         * A margin of 0.05 is wide enough to catch borderline cases
         * (e.g. children's dress vs t-shirt: ~0.04) while leaving clearly
         * confident decisions (watches vs any fashion: ~0.27) as single-category.
         *
         * Previously a forced "dresses" fallback was used for low-confidence queries,
         * which incorrectly routed jeans queries that happened to be close to dresses
         * in embedding space. Union retrieval with semantic reranking handles these
         * cases without any hard-coded category bias.
         */
        private const val LOW_CONFIDENCE_MARGIN = 0.05f

        /**
         * Maximum boost multiplier for the primary category in union retrieval.
         *
         * Scales adaptively: boost = 1 + (margin / LOW_CONFIDENCE_MARGIN) × MAX_PRIMARY_BOOST,
         * so a router that was "almost certain" applies more bias than a genuinely
         * uncertain one.
         *
         * At 0.20 the boost caps at ×1.20, which is soft enough that a catalog item
         * with a significantly better semantic score still wins regardless of category.
         */
        private const val MAX_PRIMARY_BOOST = 0.20f

        @Volatile private var instance: InferenceEngine? = null
        private val initLock = Mutex()

        suspend fun get(context: Context): InferenceEngine {
            instance?.let { return it }
            return initLock.withLock {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private suspend fun build(appCtx: Context): InferenceEngine = withContext(Dispatchers.IO) {
            Log.i(TAG, "loading bundle + 4 ORT sessions...")
            val t0 = System.nanoTime()
            val env = OrtEnvironment.getEnvironment()
            val bundle = BundleLoader(appCtx).load()
            val seg = Segmenter.load(env, copyAsset(appCtx, "models/u2netp.onnx"))
            val sig = openNnapiSession(env, copyAsset(appCtx, "models/siglip2_int8.onnx"))
            val fash = openNnapiSession(env, copyAsset(appCtx, "models/fashion_siglip_int8.onnx"))
            val dino = openNnapiSession(env, copyAsset(appCtx, "models/dinov2s_int8.onnx"))
            val tMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "InferenceEngine ready in $tMs ms (bundle: ${bundle.manifest.nItems} items, " +
                "categories=${bundle.manifest.routerCategories})")
            InferenceEngine(env, seg, sig, fash, dino, bundle)
        }

        private fun openNnapiSession(env: OrtEnvironment, file: File): OrtSession {
            val opts = OrtSession.SessionOptions().apply {
                try { addNnapi() } catch (t: Throwable) {
                    Log.w(TAG, "NNAPI unavailable for ${file.name}: ${t.message}")
                }
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return env.createSession(file.absolutePath, opts)
        }

        private fun copyAsset(context: Context, assetPath: String): File {
            val out = File(context.filesDir, assetPath)
            val assetSize = try {
                context.assets.openFd(assetPath).use { it.length }
            } catch (_: Throwable) { -1L }
            if (out.exists() && out.length() > 0 && (assetSize < 0 || out.length() == assetSize)) return out
            out.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            return out
        }
    }
}
