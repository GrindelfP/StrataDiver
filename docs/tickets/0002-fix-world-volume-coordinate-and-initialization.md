# 0002 — Make the GPU world volume correct across initialization and movement

Type: Bugfix<br>
Status: Done<br>
Depends on: 0001 for reproducible GL observations

## Problem

`WorldVolume.update()` computes `anchorX/Y/Z` in chunk coordinates (lines 120–122), then compares them directly with `originX/Y/Z`, which are cell coordinates (lines 72–77 and 124). After assigning `anchor * Chunk.SIZE`, the next update compares different units again, so the condition remains true and clears `uploaded` each frame. This defeats the intended incremental upload behavior. In addition, `glTexImage3D(..., null)` leaves newly allocated texels undefined. Unuploaded chunks and the volume before its first complete upload can therefore be stale/undefined rather than known AIR.

## Steps

1. Represent the anchor consistently (store chunk-grid anchor separately, or compare cell origin against `anchor * Chunk.SIZE`). Make unit names explicit.
2. Initialize every volume texel to the encoding of AIR with zero packed light before any sampling.
3. Preserve correct reset/repopulation behavior when the anchor changes. A chunk that did not exist at the time of the initial pass must become uploadable when generated; an absent chunk must never reveal old data from the previous volume window.
4. Ensure block edit notifications update the changed cell and any related light data that the shader actually consumes.
5. Add a testable CPU-side model for anchor transitions/upload selection if practical; retain a GL debug view/readback to verify texel contents at positive and negative chunk coordinates.

## Acceptance

- Repeated `update()` at a stationary position schedules no redundant full-window uploads.
- Crossing a chunk boundary updates the origin once and yields exactly the expected newly covered/missing chunk uploads.
- Chunks not yet generated read as AIR; moving away and back cannot expose old-window texels.
- Block place/break is reflected in the next traced frame.

## Verification and remaining checks

Run headless checks for coordinate arithmetic and compile. **A real GL playtest/readback is required** to verify texture initialization, chunk streaming, movement in positive and negative coordinates, and block edits. These GPU behaviors remain unverified until then.
