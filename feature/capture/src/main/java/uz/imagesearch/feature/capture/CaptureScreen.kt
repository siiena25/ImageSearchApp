package uz.imagesearch.feature.capture

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Camera capture screen with full CameraX integration:
 * Preview + ImageCapture, front/back switching, torch toggle,
 * pinch-to-zoom, and gallery picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onClose: () -> Unit,
    onImageSelected: (Uri) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onImageSelected(uri) }

    val cameraState = rememberCameraState()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Search by photo", color = Color.White,
                        fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                )
            )
        },
        bottomBar = {
            BottomBar(
                lensFacing = cameraState.lensFacing,
                torchOn = cameraState.torchOn,
                enabled = hasCameraPermission,
                onToggleTorch = { cameraState.toggleTorch() },
                onOpenGallery = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onShutter = {
                    val capture = cameraState.imageCapture ?: return@BottomBar
                    scope.launch {
                        runCatching { capture.takeSnapshot(ctx) }
                            .onSuccess(onImageSelected)
                    }
                },
                onSwitchCamera = { cameraState.toggleLens() },
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            if (hasCameraPermission) {
                CameraPreview(
                    state = cameraState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f) cameraState.setZoom(cameraState.zoomRatio * zoom)
                            }
                        }
                )
                if (cameraState.maxZoom > 1f && cameraState.zoomRatio > 1.05f) {
                    ZoomBadge(
                        ratio = cameraState.zoomRatio,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    )
                }
            } else {
                PermissionRequestPanel(
                    onGrantClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun ZoomBadge(ratio: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = Color.Black.copy(alpha = 0.6f),
    ) {
        Text(
            "${"%.1f".format(ratio)}×",
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun PermissionRequestPanel(
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Camera permission is required to search by photo.", color = Color.White)
        Button(onClick = onGrantClick) { Text("Grant permission") }
    }
}

@Composable
private fun BottomBar(
    lensFacing: Int,
    torchOn: Boolean,
    enabled: Boolean,
    onToggleTorch: () -> Unit,
    onOpenGallery: () -> Unit,
    onShutter: () -> Unit,
    onSwitchCamera: () -> Unit,
) {
    Surface(color = Color.Black) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onOpenGallery) {
                Icon(Icons.Default.PhotoLibrary, "Open gallery", tint = Color.White)
            }
            Spacer(Modifier.size(48.dp))

            FilledIconButton(
                onClick = onShutter,
                enabled = enabled,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White, contentColor = Color.Black,
                    disabledContainerColor = Color.White.copy(alpha = 0.4f),
                )
            ) {
                Icon(Icons.Default.RadioButtonChecked, "Take photo",
                    modifier = Modifier.size(48.dp))
            }

            IconButton(onClick = onSwitchCamera, enabled = enabled) {
                Icon(Icons.Default.Cameraswitch, "Switch camera", tint = Color.White)
            }
            IconButton(
                onClick = onToggleTorch,
                enabled = enabled && lensFacing == CameraSelector.LENS_FACING_BACK
            ) {
                Icon(
                    if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    "Toggle torch",
                    tint = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                        Color.White else Color.White.copy(alpha = 0.4f)
                )
            }
        }
    }
}

