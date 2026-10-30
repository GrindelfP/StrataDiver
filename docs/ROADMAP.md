# Rendering improvement roadmap

This roadmap tracks an evidence-first path toward clean, sharp path-traced rendering and a stable frame rate. Tickets are proposed work, not performance promises. In particular, the observed M1 Pro results do **not** establish native 1920×1080 at 60 FPS.

“FHQ” in the request is interpreted provisionally as **Full HD (1920×1080)**. Confirm whether that means framebuffer pixels or logical window size before treating it as a release target.

## Ordered work

1. **[0001 — Baseline and acceptance gate](tickets/0001-renderer-baseline-and-acceptance.md)** — Confirm the target, establish repeatable stationary/moving playtests, and measure CPU and GPU costs honestly.
2. **[0002 — World-volume correctness](tickets/0002-fix-world-volume-coordinate-and-initialization.md)** — Fix anchor units and initialize/refresh GPU world data before judging the traced image.
3. **[0003 — Native resolution and frame budgeting](tickets/0003-restore-native-resolution-through-measured-budgeting.md)** — Correct averaging and adapt using full-frame measurements; prioritize resolution and report real dimensions.
4. **[0004 — Path-tracing estimator and color correctness](tickets/0004-correct-path-tracing-estimators-and-linear-color.md)** — Audit roulette, sky/MIS contributions, and linear color handling before visual tuning.
5. **[0005 — Clear visibility/no fog](tickets/0005-remove-distance-haze-and-validate-clear-visibility.md)** — Remove default distance haze only after volume boundaries and sky visibility are correct.
6. **[0006 — Edge-aware denoising](tickets/0006-denoise-without-smearing-or-inventing-detail.md)** — Reduce noise without smearing details; keep reduced-resolution limits explicit.
7. **[0007 — Motion-aware temporal history](tickets/0007-replace-reset-only-ema-with-motion-aware-history.md)** — Reproject and validate history rather than blending stale screen pixels.
8. **[0008 — Profile-led optimization](tickets/0008-optimize-traversal-only-after-quality-and-profiling.md)** — Optimize measured costs while preserving reference output and GL 4.1 compatibility.

Tickets 0004 and 0005 may be developed in parallel after 0002, but acceptance depends on visual checks. Tickets 0006 and 0007 follow estimator correctness; motion-aware temporal reuse depends on valid current-frame guides and the denoiser's behavior being understood. Optimization is last so it does not conceal correctness or quality regressions.

## Release gate

- Confirm the target meaning and framebuffer dimensions.
- Pass offline checks and shader validation applicable to the change.
- Playtest in a real GL window at the target output size: stationary, walking, turning, shadow edges, distant terrain, caves, and block edits.
- Record median and p95 frame time, actual trace resolution, GPU identity/driver, and whether timings cover GPU work.
- Confirm the image is acceptably sharp and clean, has no unwanted haze, and shows no stale history or world-volume artifacts.
- Claim “locked 60 FPS” only after the agreed sustained run meets the frame-time gate at the confirmed target resolution. Until then, performance and visual checks remain explicitly unmet, not inferred from compilation or HUD FPS.

## Platform constraints and evidence

The project targets LWJGL/OpenGL and macOS; Apple OpenGL is capped at 4.1, so compute shaders requiring 4.3 are not a baseline option. Current playtest evidence was gathered at a 1280×720 GL surface: divisor 2 rendered 640×360 with a 47 FPS HUD reading; forced divisor 1 rendered 1280×720 with a 14 FPS HUD reading. These are observed HUD values, not controlled sustained benchmarks. Retina was disabled for the run. They demonstrate the sharpness/performance tradeoff but establish neither 1920×1080 nor locked 60 FPS. A one-off CPU JFR sample is not a GPU profile.

## Optional future directions

These are possibilities, not commitments or promises, and are outside the ordered release path unless separately prioritized:

- **Local-light sampling:** investigate an emitter index or importance-sampling structure if profiling shows local emitters are a meaningful cost or quality limitation.
- **Dynamic-object parity:** inspect whether dropped items and selection highlighting have the desired rendering and occlusion behavior in the path-traced view. Their parity is not assumed verified; create scoped work only after a real-window check identifies a gap.
- **Larger worlds:** consider streaming-window expansion or level of detail if needed, while keeping boundaries correct and visible semantics honest; fog must not be used to conceal missing or stale world data.
- **Quality/performance presets:** consider named presets only after measured tradeoffs exist, with actual trace resolution and expected cost made clear.
- **Alternative backend:** consider another graphics backend only as a separately proposed and approved project, supported by measurements showing the OpenGL 4.1 path cannot meet the confirmed target. No backend migration is implied by this roadmap.
