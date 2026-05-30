package uz.imagesearch.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uz.imagesearch.core.ml.SmokeTest

/**
 * Home screen with a search bar and a debug panel for running
 * the ML smoke-test (load all ONNX sessions, report latency/RAM).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onPhotoSearchClick: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var smokeRunning by remember { mutableStateOf(false) }
    var smokeReport by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("ImageSearch") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp)),
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp)
                    )
                    BasicTextFieldWrapper(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "Search products"
                    )
                    IconButton(onClick = onPhotoSearchClick) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Search by photo")
                    }
                }
            }

            Text(
                text = "Tap the camera icon to search by photo.\nMVP: 658 fashion items, on-device.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Text(
                text = "Debug",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    smokeRunning = true
                    smokeReport = null
                    scope.launch {
                        val report = SmokeTest.run(context)
                        smokeReport = report.pretty()
                        smokeRunning = false
                    }
                },
                enabled = !smokeRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (smokeRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Running smoke-test...")
                    }
                } else {
                    Text("Run ML smoke-test")
                }
            }
            smokeReport?.let { text ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 1.dp,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = text,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun BasicTextFieldWrapper(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = modifier
    )
}
