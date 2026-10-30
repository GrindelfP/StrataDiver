# 0001 — Establish a repeatable renderer baseline and acceptance gate

**Type:** Measurement / prerequisite
**Status: ready/done** — instrumentation implemented; measurement and acceptance remain open
**Depends on:** none

## Problem

The existing playtest establishes that quality and speed vary sharply with render scale, but not a repeatable performance baseline. `FrameTimes` measures CPU wall time only; it does not measure completed GPU work. The current FPS readout and ad-hoc runs are not enough to claim a stable 60 FPS target.

The target phrase “FHQ” is ambiguous. This plan provisionally treats it as **Full HD (1920×1080)**; confirm before setting a release gate. Existing evidence is at a 1280×720 GL surface on an M1 Pro: default scale 2 rendered at 640×360 and showed 47 FPS HUD; forced scale 1 rendered at 1280×720 and showed 14 FPS HUD. Neither run establishes native 1920×1080 performance. Do not promise 1080p60 on this hardware until measured.

## Steps

1. Confirm whether “FHQ” means 1920×1080, and whether resolution means logical window size or framebuffer pixels. Record GPU, OS, GL renderer/version, display scaling, window/framebuffer dimensions, and build revision with every run.
2. Add a repeatable benchmark mode or documented playtest script: fixed camera/scene, warm-up period, timed capture period, and recorded frame-time percentiles (at least median and p95), render dimensions/divisor, and dropped/invalid frames.
3. Separate CPU stage timings from GPU pass timings. Use OpenGL timer queries when supported; avoid synchronous readback/stalls in the normal benchmark path. Report limits where a GPU measurement is unavailable.
4. Capture stationary and moving/turning-camera runs at each tested render size. Include representative sunlit terrain, shadow edges, and a cave/occlusion case.
5. Keep screenshot comparisons at the actual framebuffer size; do not judge a scaled screenshot as native-resolution output.

## Acceptance

- A second run with the same setup can reproduce the reported dimensions and comparable timing; reports include median and p95 rather than a single HUD FPS sample.
- The benchmark distinguishes CPU time, GPU time (or explicitly unavailable), and presentation/wait time.
- The product target and framebuffer-vs-window interpretation are explicitly confirmed before a 60 FPS release gate is declared.
- No 1080p60 performance claim is made without sustained measured evidence on the target hardware.

## Verification and remaining checks

Compile and run any headless checks affected by instrumentation. **Real GL-window playtesting is required** to collect GPU timings and inspect the rendered scene. Until that playtest is performed, GPU cost, sustained target performance, and visual acceptability remain unverified.

## Repeatable baseline procedure (implemented)

Run `tools/renderer-baseline.sh` from the repository root on the target machine. It compiles, performs 120 warm-up frames and records up to 599 timed frames by default (the 600th frame is reserved for capture); `WARMUP_FRAMES`, `FRAMES`, and `CAPTURE` override these values. `FRAMES` must be greater than one. It starts from the same spawn and does not inject camera motion, so this is a stationary run. The run writes a final PNG and reports revision, OS, GL vendor/renderer/version, requested window size, GLFW-reported framebuffer size, actual GL surface size, trace dimensions/divisor, and CPU-wall median/p95. Repeat with the same revision, settings, display configuration, and scene. For movement/turning, cave/occlusion, and sunlit/shadow-edge cases, use a separate manually recorded playtest; these are not represented by the stationary capture.

The current capture mode reads back a PNG on its final frame and that readback synchronizes the GPU; that frame is deliberately excluded from the timing distribution. Per-frame CPU wall timing includes any driver or swap/presentation blocking and therefore is not a pure CPU-work or presentation-wait breakdown. GPU execution time is explicitly unavailable: this step does not add timer queries. Do not interpret the displayed FPS/HUD or CPU wall report as a GPU timing or release gate. Screenshots are saved at the actual GL surface dimensions, not enlarged to a presumed native output.

**Still unresolved by design:** “FHQ” may mean 1920×1080, but this is not confirmed. The meaning of resolution (logical window versus framebuffer pixels) also needs user confirmation. No 60 FPS target, 1080p60 promise, or release gate is established by ticket 0001. A GL-window run is required to validate the procedure, collect real measurements, and decide whether asynchronous OpenGL timer queries can be used reliably on the target driver.
