# ImageSearchApp

Android application for on-device visual product search. Point your camera at a clothing item and get the top-10 most visually similar products from the catalog — no internet connection required.

The on-device pipeline mirrors the offline Python pipeline used to build the catalog bundle.

## Architecture

```
:app                    MainActivity, NavGraph (Home → Capture → Results)
:feature:home           HomeScreen — search bar + camera entry point
:feature:capture        CaptureScreen — CameraX preview, torch, zoom, gallery picker
:feature:results        ResultsScreen — top-10 results grid with score breakdown
:core:ml                ONNX Runtime sessions, Preprocess, Segmenter, InferenceEngine
:core:retrieval         ColorSignatureV10 (1:1 port of color_v10.py), CosineRetriever
:core:catalog           BundleLoader — loads binary catalog assets into FloatBuffers
:core:ui                Material3 theme
```

## How it works

1. **Segmentation** — U²-Netp crops the clothing item from the background (CPU-only; NNAPI is slower for this model due to ConvTranspose2d fallback).
2. **Routing** — FashionSigLIP embeddings of the raw image are compared against per-category visual prototypes to pick the retrieval category. Low-confidence cases trigger union retrieval over top-2 categories.
3. **Encoding** — FashionSigLIP (clothing) or SigLIP2 (watches) encodes the segmented crop. DINOv2-S adds patch-level texture features.
4. **Color** — `ColorSignatureV10` extracts a 26-d hue histogram signature (monochrome-aware).
5. **Retrieval** — brute-force cosine search with triple rerank (sem × 0.55 + tex × 0.20 + col × 0.25) and a soft color gate.

Typical latency on Pixel 10 after warm-up: **~0.7 s** end-to-end.

## Bundle assets

The following files must be placed in `app/src/main/assets/` before building:

```
assets/
├── models/
│   ├── fashion_siglip_int8.onnx
│   ├── siglip2_int8.onnx
│   ├── dinov2s_int8.onnx
│   └── u2netp.onnx
└── catalog/
    ├── manifest.json
    ├── catalog.json
    ├── embeddings.bin
    ├── texture.bin
    ├── color_signatures.bin
    ├── category_text_embeddings.bin
    ├── category_visual_prototypes.bin
    └── thumbnails/<product_id>.jpg
```

Generate with the companion Python project:

```bash
python3 export_models.py               # → models/*.onnx
python3 export_catalog_for_android.py  # → android_bundle/
```

Then copy the outputs into the Android assets directory.

## Building

1. Open the project root in Android Studio (Iguana or later).
2. Let Gradle sync — all dependencies are pulled from Maven Central.
3. Deploy to a physical device or emulator (minSdk 26, tested on Pixel 10 / Android 16).

## Tech stack

| Component | Library |
|---|---|
| UI | Jetpack Compose + Material3 |
| Camera | CameraX 1.4 |
| ML inference | ONNX Runtime for Android |
| Image loading | Coil 3 |
| DI | Hilt |
| Navigation | Navigation Compose |
