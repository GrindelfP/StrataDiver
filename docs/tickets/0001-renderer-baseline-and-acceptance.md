# 0001 — Establish a repeatable renderer baseline and acceptance gate

Type: Measurement / prerequisite<br>
Status: In progress — measurement evidence pending<br>
Depends on: none

## Problem

The existing playtest establishes that quality and speed vary sharply with render scale, but not a repeatable performance baseline. `FrameTimes` measures CPU wall time only; it does not measure completed GPU work. The current FPS readout and ad-hoc runs are not enough to claim a stable 60 FPS target.

The user confirmed that **FHQ means Full HD (1920×1080)**, but that is a later target. The current goal is true **1280×720 rendered and shown output pixels**: both the trace image and presented GL surface must be 1280×720, not a lower-resolution trace upscaled into a 1280×720 window. Resolution is therefore judged in actual output pixels, not by logical window dimensions. Existing observations on an M1 Pro include 1280×720 output with scale 2 (640×360 trace, 47 FPS HUD) and scale 1 (1280×720 trace, 14 FPS HUD); these are historical single HUD readings, not repeatable baseline evidence. Neither establishes performance at 1920×1080. Do not promise 60 FPS at either target without sustained measured evidence.

## Steps

1. Record the confirmed current target (1280×720 true rendered and shown output pixels) and later FHQ target (1920×1080). Record GPU, OS, GL renderer/version, display scaling, window/framebuffer/surface dimensions, and build revision with every run. Keep logical window size distinct from actual GL output pixels.
2. Add a repeatable benchmark mode or documented playtest script: fixed camera/scene, warm-up period, timed capture period, and recorded frame-time percentiles (at least median and p95), render dimensions/divisor, and dropped/invalid frames.
3. Separate CPU stage timings from GPU pass timings. Use OpenGL timer queries when supported; avoid synchronous readback/stalls in the normal benchmark path. Report limits where a GPU measurement is unavailable.
4. Capture stationary and moving/turning-camera runs at each tested render size. Include representative sunlit terrain, shadow edges, and a cave/occlusion case.
5. Keep screenshot comparisons at the actual framebuffer size; do not judge a scaled screenshot as native-resolution output.

## Acceptance

- A second run with the same setup can reproduce the reported dimensions and comparable timing; reports include median and p95 rather than a single HUD FPS sample.
- The benchmark distinguishes CPU time, GPU time (or explicitly unavailable), and presentation/wait time.
- The product target is explicitly confirmed as 1280×720 true rendered-and-shown output pixels; FHQ is 1920×1080 as a later target. A 60 FPS release gate is not declared until sustained measurements support it.
- No 1080p60 performance claim is made without sustained measured evidence on the target hardware.

## Verification and remaining checks

Compile and run any headless checks affected by instrumentation. **Real GL-window playtesting is required** to collect GPU timings and inspect the rendered scene. Until that playtest is performed, GPU cost, sustained target performance, and visual acceptability remain unverified.

## Repeatable baseline procedure (implemented)

Run `tools/renderer-baseline.sh` from the repository root on the target machine, with `JAVA_TOOL_OPTIONS` including `-Dstratadiver.gpuTimers=true -Dstratadiver.scale=1` for the current 1280×720 target. It compiles, performs 120 warm-up frames and records up to 599 timed frames by default (the 600th frame is reserved for capture); `WARMUP_FRAMES`, `FRAMES`, and `CAPTURE` override these values. `FRAMES` must be greater than one. It starts from the same spawn and does not inject camera motion, so this is a stationary run. The run writes a final PNG and reports revision, OS, GL vendor/renderer/version, requested window size, GLFW-reported framebuffer size, actual GL surface size, trace dimensions/divisor, and CPU-wall median/p95. Repeat with the same revision, settings, display configuration, and scene. For movement/turning, cave/occlusion, and sunlit/shadow-edge cases, use a separate manually recorded playtest; these are not represented by the stationary capture.

The current capture mode reads back a PNG on its final frame and that readback synchronizes the GPU; that frame is deliberately excluded from the CPU-wall timing distribution. Per-frame CPU wall timing includes any driver or swap/presentation blocking and therefore is not a pure CPU-work or presentation-wait breakdown. With `-Dstratadiver.gpuTimers=true`, asynchronous `GL_TIME_ELAPSED` queries report trace, resolve, and present-pass GPU work when results become available; they do not include swap/presentation waiting and are not whole-frame GPU time. Without the property, GPU timing is unavailable. Do not interpret the displayed FPS/HUD or CPU wall report as a GPU timing or release gate. Screenshots are saved at the actual GL surface dimensions, not enlarged to a presumed native output.

**Still pending:** collect and preserve a real stationary run at 1280×720 output pixels, then repeat it to establish reproducibility; collect a 1920×1080 run only as later-target evidence. A moving/turning run and representative cave/shadow-edge visual checks are also outstanding. No 60 FPS target or performance promise is established by this ticket. The asynchronous timer-query path is implemented but its behavior on the target GL driver still needs live validation.
