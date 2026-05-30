package uz.imagesearch.feature.results

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.imagesearch.core.ml.InferenceEngine
import uz.imagesearch.core.retrieval.RetrievalResult

/**
 * Drives the search pipeline for [ResultsScreen]:
 *   Loading → Success(category, items, latency) | Error(message)
 *
 * [InferenceEngine] is a process-level singleton, so sessions stay warm
 * across screen recompositions and ViewModel recreation.
 */
class ResultsViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Success(
            val category: String,
            val results: List<RetrievalResult>,
            val totalMs: Long,
            val breakdown: InferenceEngine.LatencyBreakdown,
        ) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun runQuery(uri: Uri) {
        if (_state.value is State.Loading) return
        _state.value = State.Loading
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val bitmap = loadBitmap(uri) ?: error("Cannot decode bitmap from $uri")
                val engine = InferenceEngine.get(app)
                val out = engine.runQuery(bitmap)
                bitmap.recycle()
                _state.value = State.Success(
                    category = out.category,
                    results = out.results,
                    totalMs = out.latencyMs.total,
                    breakdown = out.latencyMs,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "query failed", t)
                _state.value = State.Error("${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val ctx = getApplication<Application>()
        return ctx.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }?.let { bm ->
            // Downscale to max side 1024 — larger images don't improve retrieval quality
            // but do increase memory and preprocessing time.
            val max = maxOf(bm.width, bm.height)
            if (max <= 1024) bm
            else {
                val s = 1024f / max
                val w = (bm.width * s).toInt()
                val h = (bm.height * s).toInt()
                val scaled = Bitmap.createScaledBitmap(bm, w, h, true)
                if (scaled !== bm) bm.recycle()
                scaled
            }
        }
    }

    companion object {
        private const val TAG = "ResultsVM"
    }
}

