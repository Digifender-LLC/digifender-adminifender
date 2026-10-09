# Local model manifests

Manifests and evaluation records for the on-device model ([TDD](../docs/specifications/tdd.md#version-freeze-and-model-manifest)). Model bytes never go in this repository or in the app.

**Status:** deferred by the owner on 2026-10-04; no manifest is pinned. The selected profile is Gemma 3 1B IT int4 for LiteRT-LM with a 2,048-token context, at most 1,536 input and 256 output tokens. An artifact is pinned only after it is obtained through its documented authorized download and evaluated on the owner's S24+.

## Manifest format

One JSON object per model version, matching `ModelManifest` in `android/core/data/.../device/ModelManifest.kt`:

```json
{
  "modelId": "gemma-3-1b-it-int4",
  "artifactUrl": "https://… (documented download page, not a token-bearing URL)",
  "sha256": "64 lowercase hex characters",
  "byteLength": 0,
  "licenseUrl": "https://…",
  "runtimeVersion": "litertlm-android 0.17.1",
  "contextTokens": 2048,
  "inputLimit": 1536,
  "outputLimit": 256,
  "tokenizerDigest": "digest of the tokenizer/template shipped in the artifact"
}
```

Unknown fields are rejected, and `inputLimit + outputLimit` must fit `contextTokens`. The app never applies more than 1,536 input or 256 output tokens, whatever the manifest says. A changed `sha256` is a new model version that needs its own evaluation.

## Using a manifest

Build with `-Padminifender.modelManifest=../models/<file>.json` (path relative to `android/`). The manifest is embedded in the app; without one, Settings reports that no local model is configured.

On the phone the owner chooses the downloaded model file in Settings. The app copies it into no-backup app storage while hashing it, and activates it only if the size and SHA-256 match. Work routed to the phone stays visibly queued until then.

## Known gap

LiteRT-LM 0.17.1 has no public tokenizer call. The adapter therefore counts input as UTF-8 bytes plus framing, an upper bound on tokens. Inputs that might exceed 1,536 tokens are declined back to the server (`input_too_large`), never truncated. Measuring real token counts, latency and memory on the S24+ is part of device acceptance.
