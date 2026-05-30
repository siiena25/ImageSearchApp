package uz.imagesearch.core.retrieval

import uz.imagesearch.core.catalog.LoadedBundle
import kotlin.math.sqrt

/**
 * Triple rerank weights — see retrieve_topk_multicat.py for the offline equivalent.
 *
 * Weights are biased toward semantics over color: aggressive color rerank
 * (e.g. 0.40 col) tends to over-prioritize palette at the expense of
 * silhouette/style similarity. The current balance keeps color as a secondary
 * tiebreaker rather than a primary signal.
 *
 *   sem = 0.55 — overall visual similarity (FashionSigLIP)
 *   tex = 0.20 — patch-level pattern/texture (DINOv2)
 *   col = 0.25 — palette match (ColorSignatureV10)
 */
object TripleRerank {
    const val ALPHA = 0.55f
    const val BETA = 0.20f
    const val GAMMA = 0.25f
}

/** Single top-K result. */
data class RetrievalResult(
    val productId: String,
    val title: String,
    val thumbAssetPath: String,
    val finalScore: Float,
    val semanticScore: Float,
    val textureScore: Float,
    val colorScore: Float,
)

/**
 * Brute-force cosine retrieval within the predicted categories + triple rerank.
 *
 * @param queryEmbedding L2-normalized query vector (encoder output).
 * @param queryTexture   L2-normalized DINOv2 vector, or null to skip texture scoring.
 * @param queryColor     ColorSignatureV10.Signature extracted from the query crop.
 * @param categories     Router output — only items from these categories are scored.
 *                       May contain 1 or 2 entries; when the router is uncertain
 *                       a union of top-2 categories is used (see InferenceEngine).
 *                       All categories must share the same encoder space
 *                       (FashionSigLIP); watches (SigLIP2) are never mixed in.
 * @param primaryCategory When categories.size > 1, items from this category receive
 *                       a small score boost to counteract catalog size imbalance
 *                       (e.g. 399 dresses vs 66 jeans in the same retrieval pool).
 * @param primaryBoost   Multiplier applied to primary-category items. Computed
 *                       adaptively from the router margin in InferenceEngine.
 */
class CosineRetriever(private val bundle: LoadedBundle) {

    fun retrieve(
        queryEmbedding: FloatArray,
        queryTexture: FloatArray?,
        queryColor: ColorSignatureV10.Signature,
        categories: Set<String>,
        topK: Int = 10,
        primaryCategory: String? = null,
        primaryBoost: Float = 1.0f,
    ): List<RetrievalResult> {
        val items = bundle.items.filter { it.category in categories }
        if (items.isEmpty()) return emptyList()

        val embDim = bundle.manifest.embeddingDim
        val texDim = bundle.manifest.textureDim
        val colDim = bundle.manifest.colorSignatureDim

        data class Scored(val item: uz.imagesearch.core.catalog.CatalogItem,
                          val sem: Float, val tex: Float, val col: Float)
        val scored = ArrayList<Scored>(items.size)

        val embRow = FloatArray(embDim)
        val texRow = FloatArray(texDim.coerceAtLeast(1))
        val colRow = FloatArray(colDim)

        for (it in items) {
            copyRow(bundle, bundle.embeddings, it.index, embDim, embRow)
            val sem = dot(queryEmbedding, embRow)

            val tex = if (queryTexture != null && bundle.texture != null && texDim > 0) {
                copyRow(bundle, bundle.texture!!, it.index, texDim, texRow)
                val n = norm(texRow)
                if (n > 1e-6f) (dot(queryTexture, texRow) / n).coerceIn(0f, 1f) else 0f
            } else 0f

            copyRow(bundle, bundle.colorSignatures, it.index, colDim, colRow)
            val cSig = ColorSignatureV10.fromVector(colRow)
            val col = ColorSignatureV10.similarity(queryColor, cSig)

            scored.add(Scored(it, sem, tex, col))
        }

        // Boost only makes sense in a union retrieval — skip the check in single-category queries.
        val applyBoost = primaryCategory != null && categories.size > 1 && primaryBoost > 1.0f

        fun finalScoreOf(s: Scored): Float {
            val base = TripleRerank.ALPHA * s.sem + TripleRerank.BETA * s.tex + TripleRerank.GAMMA * s.col
            val gated = base * ColorSignatureV10.gate(s.col)
            return if (applyBoost && s.item.category == primaryCategory) gated * primaryBoost else gated
        }

        scored.sortByDescending(::finalScoreOf)

        return scored.take(topK).map { s ->
            RetrievalResult(
                productId = s.item.productId,
                title = s.item.title,
                thumbAssetPath = s.item.thumbAssetPath,
                finalScore = finalScoreOf(s),
                semanticScore = s.sem,
                textureScore = s.tex,
                colorScore = s.col,
            )
        }
    }

    private fun copyRow(b: LoadedBundle, buf: java.nio.FloatBuffer,
                        index: Int, dim: Int, dst: FloatArray) {
        synchronized(buf) {
            buf.position(index * dim)
            buf.get(dst, 0, dim)
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun norm(a: FloatArray): Float {
        var s = 0f
        for (v in a) s += v * v
        return sqrt(s)
    }
}
