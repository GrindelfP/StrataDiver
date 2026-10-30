# 0003 — Restore native output resolution with an honest frame budget

**Type:** Renderer / performance
**Status:** Proposed
**Depends on:** 0001, 0002

## Problem

At the default divisor 2, the observed render was 640×360 on a 1280×720 surface. Forcing divisor 1 produced visibly sharper output but a 14 FPS HUD reading in the observed run. A larger divisor or spatial denoiser cannot recover detail that was never rendered. The adaptive controller needs measured end-to-end cost, not just CPU trace wall time.

`FrameTimes.end()` decays accumulated totals by 0.95 after 60 frames but resets `samples` to 1, so its displayed averages are not a consistent rolling average. Inspect and correct this as part of instrumentation, not as a performance result. The controller must include tracing, accumulation, denoise, temporal/present passes, and relevant CPU work; GPU time must be measured asynchronously where possible.

## Steps

1. Fix the frame-time averaging window and expose actual render dimensions and scale in the diagnostic overlay/log.
2. Measure the full frame cost using ticket 0001's methodology. Keep GPU-query latency/polling from stalling the frame loop.
3. Set the initial quality policy to native framebuffer resolution. If the target cannot be held, degrade resolution in documented steps rather than silently presenting an upscale as native quality.
4. Tune resolution adaptation against a stated frame-time budget, using smoothed observations, bounded response, and hysteresis to prevent rapid oscillation. Do not use an individual cold frame as a decision.
5. Compare resolution and frame-time tradeoffs on the target hardware; only adopt a lower steady-state resolution if the user-facing quality/performance target allows it.

## Acceptance

- Reported averages match the frames actually included in the rolling window.
- The active render size is observable and corresponds to the output framebuffer and selected policy.
- Scale changes are stable, bounded, and based on measured end-to-end cost.
- No claim of locked 60 FPS is accepted without a sustained real-window run at the confirmed target resolution, including camera motion.

## Verification and remaining checks

Headless tests can validate rolling-average and controller arithmetic. **Real GL playtesting is required** for GPU timing, visible sharpness, judder, and sustained 60 FPS. If the target cannot be met, record measured results and propose a tradeoff rather than promising it.
