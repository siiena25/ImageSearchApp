package uz.imagesearch.core.catalog

/**
 * Single catalog item. Order in [items] matches the row order in
 * `embeddings.bin`, `texture.bin`, `color_signatures.bin`.
 */
data class CatalogItem(
    val productId: String,
    val category: String,
    val encoder: String,
    val title: String,
    val thumbAssetPath: String,
    /** Row index into the flat float buffers (embeddings / texture / color). */
    val index: Int,
)

data class BundleManifest(
    val version: Int,
    val nItems: Int,
    val embeddingDim: Int,
    val textureDim: Int,
    val colorSignatureDim: Int,
    val routerCategories: List<String>,
    /** "visual_prototypes" (v2) or "text_embeddings" (v1). */
    val routerMethod: String,
    val thumbSize: Int,
)
