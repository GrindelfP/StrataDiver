# Renderer handoff

## Goal

Make the path-traced renderer sharp, clean, and stable at 60 fps. “FHQ” was used to
describe the desired resolution; confirm whether this means Full HD (1920×1080) before
setting the target. No source changes have been made for this work yet.

## Current state and evidence

- The active renderer is `GpuTracer`: OpenGL 4.1 fullscreen fragment passes. `F` does
  not switch to a rasterizer; that description in `AGENTS.md` is outdated.
- Playtested on an M1 Pro (14-core GPU), 1280×720 GL surface with Retina scaling disabled.
  Walking, turning, and standing still showed roughly 34–60 FPS across views; this was not
  a controlled benchmark.
- `tools/shot.sh` at its settled default scale 2 produced a 640×360 render enlarged to
  1280×720 and about 47 FPS. Forced scale 1 at 1280×720 was sharper but about 14 FPS.
  A no-denoise scale-2 run was about 42 FPS; these runs are not controlled comparisons.
- Printed capture frame/stage timings are not trustworthy: `FrameTimes` has a known
  averaging/reset defect. Startup-inclusive JFR samples showed chunk uploads among top
  CPU execution samples, but do not constitute a GPU profile.
- A sampler warning about an unloaded `GL_TEXTURE_INDEX_2D` occurred on one early run and
  did not recur on a later launch. Cause remains unknown.

## Confirmed issues vs. hypotheses

- **Confirmed from inspection:** `WorldVolume.update` compares chunk-anchor coordinates
  with cell-origin coordinates and consequently clears its upload cache each frame in the
  common case. This is a candidate performance bug; validate its effect and any stale or
  missing volume cells before changing it.
- **Confirmed from inspection:** `FrameTimes.end` retains a weighted total while resetting
  sample count to 1, inflating averages used by adaptive path scale. Its CPU submission
  timing also does not measure GPU completion.
- **Confirmed visual tradeoff:** default adaptive scale can render below output resolution;
  upscaling cannot recover detail. Denoising/sharpening cannot restore detail never traced.
- **Confirmed configuration:** `FOG_DENSITY = 0.002` deliberately adds distance haze.
- **Confirmed accumulation behavior:** `uSampleIndex` is tied to sample count, so resets
  reuse the same noise sequence. There is no temporal reprojection. The EMA expression
  gives 92% weight to the new sample despite comments saying otherwise; reset blending can
  darken a newly cleared frame.
- **Potential lighting bias to validate:** `trace.frag` skips sky NEE at depth ≥3 while
  retaining an escaping-bounce weight of 0.5. Russian-roulette survival compensation is
  also absent.
- Native 1080p60 cannot be promised from current evidence. It requires trustworthy GPU
  timing and optimization, especially traversal and temporal reuse.

## Pending work / next actions

Another agent is drafting tickets and a roadmap; neither is implementation or evidence
that a fix has landed. Proposed order: repair timing and validate upload-cache behavior;
make target resolution explicit and remove haze for a clean baseline; validate lighting;
add temporal reprojection with disocclusion handling and a lighting-aware denoiser; then
optimize against GPU timings. Confirm “FHQ” before locking the resolution target.

Acceptance should be measured in motion at the agreed native resolution, targeting a
16.67 ms frame budget, across foliage, interiors, edits, and streaming—not a settled
still screenshot. Re-run controlled comparisons after fixing the measurement path. Anything
requiring GL remains subject to a real-window playtest.
