package uz.imagesearch.core.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Bundle loader from `assets/catalog/`.
 *
 * Reads `.bin` files entirely into DirectByteBuffers (LITTLE_ENDIAN).
 * Files are small enough (~10 MB total) that full upfront loading keeps
 * things simple and avoids partial-read edge cases. For larger catalogs,
 * `AssetFileDescriptor + FileChannel.map()` would be more appropriate
 * (requires `noCompress` for uncompressed assets, which we already set).
 */
class BundleLoader(private val context: Context) {

    fun load(): LoadedBundle {
        val manifest = readManifest()
        val catalog = readCatalog(manifest)
        val embeddings = readFloatBuffer("catalog/embeddings.bin",
            manifest.nItems * manifest.embeddingDim)
        val texture = if (manifest.textureDim > 0)
            readFloatBuffer("catalog/texture.bin",
                manifest.nItems * manifest.textureDim) else null
        val color = readFloatBuffer("catalog/color_signatures.bin",
            manifest.nItems * manifest.colorSignatureDim)
        val categoryText = readFloatBuffer("catalog/category_text_embeddings.bin",
            manifest.routerCategories.size * manifest.embeddingDim)
        // Visual prototypes are absent in bundle v1 — fall back to text routing gracefully.
        val visualProto = try {
            readFloatBuffer(
                "catalog/category_visual_prototypes.bin",
                manifest.routerCategories.size * manifest.embeddingDim,
            )
        } catch (_: Throwable) { null }
        return LoadedBundle(manifest, catalog, embeddings, texture, color, categoryText, visualProto)
    }

    private fun readManifest(): BundleManifest {
        val json = readJson("catalog/manifest.json").let { JSONObject(it) }
        val cats = json.getJSONArray("router_categories")
        return BundleManifest(
            version = json.getInt("version"),
            nItems = json.getInt("n_items"),
            embeddingDim = json.getInt("embedding_dim"),
            textureDim = json.getInt("texture_dim"),
            colorSignatureDim = json.getInt("color_signature_dim"),
            routerCategories = (0 until cats.length()).map { cats.getString(it) },
            routerMethod = json.optString("router_method", "text_embeddings"),
            thumbSize = json.getInt("thumb_size"),
        )
    }

    private fun readCatalog(manifest: BundleManifest): List<CatalogItem> {
        val arr = JSONArray(readJson("catalog/catalog.json"))
        require(arr.length() == manifest.nItems) {
            "catalog.json size ${arr.length()} != manifest.n_items ${manifest.nItems}"
        }
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            CatalogItem(
                productId = o.getString("product_id"),
                category = o.getString("category"),
                encoder = o.optString("encoder", "fashion"),
                title = o.optString("title", ""),
                thumbAssetPath = "catalog/${o.getString("thumb")}",
                index = i,
            )
        }
    }

    private fun readJson(assetPath: String): String {
        val out = ByteArrayOutputStream()
        context.assets.open(assetPath).use { it.copyTo(out) }
        return out.toString(Charsets.UTF_8)
    }

    private fun readFloatBuffer(assetPath: String, expectedFloats: Int): FloatBuffer {
        val bytes = ByteArray(expectedFloats * 4)
        context.assets.open(assetPath).use { input ->
            var off = 0
            while (off < bytes.size) {
                val r = input.read(bytes, off, bytes.size - off)
                require(r > 0) { "Unexpected EOF in $assetPath at $off / ${bytes.size}" }
                off += r
            }
        }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(bytes).rewind()
        return buf.asFloatBuffer()
    }
}

data class LoadedBundle(
    val manifest: BundleManifest,
    val items: List<CatalogItem>,
    val embeddings: FloatBuffer,    // [n × embeddingDim]
    val texture: FloatBuffer?,      // [n × textureDim] or null
    val colorSignatures: FloatBuffer, // [n × colorSignatureDim]
    val categoryText: FloatBuffer,  // [k × embeddingDim] — text routing (v1)
    /**
     * Visual prototypes — mean FashionSigLIP embedding of the catalog per category.
     * `null` if bundle is v1 build (text routing only). Used with priority
     * over `categoryText` if present, see [InferenceEngine].
     */
    val categoryVisualPrototypes: FloatBuffer?,
) {
    /** Copy a row into a FloatArray (for cosine). */
    fun row(buf: FloatBuffer, index: Int, dim: Int): FloatArray {
        val out = FloatArray(dim)
        synchronized(buf) {
            buf.position(index * dim)
            buf.get(out, 0, dim)
        }
        return out
    }
}
