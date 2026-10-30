# 0006 — Make denoising edge-aware and honest about missing detail

**Type:** Image quality
**Status: implemented; live visual verification remains**
**Depends on:** 0003, 0004

## Problem

`denoise.frag` is a 3×3 bilateral filter with normal/depth/albedo guides. It can reduce local noise but also changes edges and authored texture detail; it cannot reconstruct detail lost by rendering at a fraction of the output resolution. Its `uTexel` correctly refers to the trace dimensions, so validate that all guide buffers and filters stay aligned when scale changes. Avoid treating sharpening as recovered information.

## Steps

1. Establish raw accumulated radiance as a debug/view mode and compare it with denoised output at each render scale.
2. Add small deterministic image fixtures for a flat region, a sharp silhouette, adjacent voxel faces, texture detail, and sky/background. Assert bounded flat-region noise reduction and bounded edge/color leakage.
3. Tune kernel/radius/weights from those measurements. Prefer a multi-scale/à-trous approach only if the current filter demonstrably fails; retain a disabled/debug option for comparison.
4. Test the filter at native and reduced scales while the camera moves. Ensure normal/depth/albedo guides describe the same current sample and no stale guide data crosses a reset.
5. Keep upscaling and any sharpening explicit and restrained; report trace resolution separately from display resolution.

## Acceptance

- Noise reduction is measurable on flat regions without unacceptable silhouette, face, or texture-edge bleeding.
- No claim that filtering restores native detail from a reduced-resolution trace.
- Raw-vs-filtered comparison is available during development and all passes respond correctly to scale changes.

## Verification and remaining checks

Image fixtures can test filter math offline if extracted into a testable path. **A real GL playtest** must compare motion, edges, texture detail, and perceived sharpness; filter quality and temporal artifacts remain unverified until then.

## Implementation notes

- `BilateralDenoiser` is the deterministic CPU reference for the shader's 3×3 weights. `tools/Check.kt` covers flat-region variance reduction and bounded leakage at depth, normal, albedo, and sky boundaries.
- The GPU filter uses the same tighter depth/albedo thresholds. Radiance and both guide textures are allocated together at trace dimensions, overwritten together per sample, and sampled with trace-sized `uTexel`; resize replaces them together and reset clears temporal history.
- `-Dstratadiver.rawTrace=true` shows the progressive average before denoising/EMA/sharpening. Sharpening is opt-in (`-Dstratadiver.sharpen=N`, default 0); it is not detail reconstruction. The existing scale report gives trace dimensions alongside display dimensions.
- Offline verification cannot establish motion stability or perceptual quality at native/reduced scale. Those require a live GL playtest.
