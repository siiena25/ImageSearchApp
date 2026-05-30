package uz.imagesearch.feature.results

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import uz.imagesearch.core.retrieval.RetrievalResult

/**
 * Search results screen. Runs the full on-device pipeline via [ResultsViewModel]
 * and shows a 2-column grid of top-10 matches with score breakdown.
 * Displays a progress indicator during inference and an error panel with retry on failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(queryUri: String, onBack: () -> Unit) {
    val vm: ResultsViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(queryUri) {
        vm.runQuery(Uri.parse(queryUri))
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Top-10 matches") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QueryBanner(queryUri)
            when (val s = state) {
                ResultsViewModel.State.Idle -> Spacer(Modifier.height(0.dp))
                ResultsViewModel.State.Loading -> LoadingPanel()
                is ResultsViewModel.State.Error -> ErrorPanel(s.message) {
                    vm.runQuery(Uri.parse(queryUri))
                }
                is ResultsViewModel.State.Success -> {
                    LatencyHeader(s)
                    if (s.results.isEmpty()) {
                        EmptyPanel(s.category)
                    } else {
                        ResultsGrid(s.results, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryBanner(uri: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = uri,
                contentDescription = "Query",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.LightGray)
            )
            Text(
                "Query",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingPanel() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            "Running pipeline (segment - route - encode - retrieve)...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "First call ~1.5 s (warming sessions). Next searches ~0.7 s.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Pipeline failed",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyPanel(category: String) {
    Text(
        "No items in catalog for category '$category'.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LatencyHeader(s: ResultsViewModel.State.Success) {
    val b = s.breakdown
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Category: ${s.category} - ${s.totalMs} ms total",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "segment ${b.segmentMs} - route ${b.routeMs} - encode ${b.encodeMs} - " +
                    "tex ${b.textureMs} - color ${b.colorMs} - retr ${b.retrievalMs} ms",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ResultsGrid(items: List<RetrievalResult>, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(items, key = { it.productId }) { item -> ResultCard(item) }
    }
}

@Composable
private fun ResultCard(item: RetrievalResult) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Column {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data("file:///android_asset/${item.thumbAssetPath}")
                    .crossfade(true)
                    .build(),
                contentDescription = item.title.ifBlank { item.productId },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFFEEEEEE)),
            )
            Column(Modifier.padding(8.dp)) {
                Text(
                    item.title.ifBlank { "id ${item.productId}" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "score %.3f / sem %.2f / col %.2f".format(
                        item.finalScore, item.semanticScore, item.colorScore
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
