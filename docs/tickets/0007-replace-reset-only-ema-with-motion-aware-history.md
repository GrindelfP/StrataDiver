# 0007 — Use motion-aware temporal accumulation instead of reset-only EMA

**Type:** Image quality / temporal stability
**Status: ready/done**
**Depends on:** 0002, 0004, 0006

## Problem

`GpuTracer` resets progressive accumulation and clears EMA history whenever `sceneHasMoved()` detects camera/world changes. It then mixes the current frame with cleared history. In `ema.frag`, `mix(hist, curr, uAlpha)` with the configured high current-frame weight (0.92 per shader comment) means only a small fraction of history is retained; this is not a 12-frame half-life as the comment claims. More importantly, un-reprojected history is not valid at the same pixel after camera movement, so increasing history weight would create ghosting rather than correct accumulation.

## Steps

1. Correct the EMA documentation/weight interpretation and add tests for the chosen response curve. Do not increase history weight as a substitute for reprojection.
2. Implement camera-aware reprojection using prior camera transforms and suitable surface data (depth/normal/position), with rejection for disocclusion, invalid history, and scene/world edits.
3. Clamp or validate reprojected history against current neighborhood/radiance to avoid trails and fireflies; reset history when camera/world continuity cannot be established.
4. Separate stationary progressive accumulation from moving-camera temporal reuse. Preserve exact stationary convergence behavior.
5. Add static-scene and controlled camera-pan fixtures; expose a debug view for history validity/rejection.

## Acceptance

- Stationary camera produces the same converged result as the progressive reference.
- Camera motion reuses valid history without visible ghost trails or stale geometry; disocclusions resolve promptly.
- World edits invalidate affected history or otherwise cannot leave old block silhouettes behind.
- Temporal smoothing behavior is described by measured response, not the current incorrect half-life comment.

## Verification and remaining checks

Reprojection math and stationary fixtures can be checked offline where possible. **Real GL playtesting with walking, turning, stopping, and editing blocks is required**. Motion stability and ghosting remain unverified until that playtest.
