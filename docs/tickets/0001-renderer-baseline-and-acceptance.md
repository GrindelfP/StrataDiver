# 0001 — Establish a repeatable renderer baseline and acceptance gate

Type: Measurement / prerequisite<br>
Status: In progress — 1280×720 baseline measured; acceptance checks remain<br>
Depends on: none

## Problem

Earlier playtests established that quality and speed vary sharply with render scale, but did not provide a repeatable performance baseline. `FrameTimes` reports CPU wall time, which includes driver/swap blocking; the benchmark now supplements it with asynchronous per-pass GPU queries and a separate swap-call wall distribution. The short captures below are not enough to claim sustained 60 FPS performance.

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

The procedure has been run in a real GL window on the M1 Pro; see the preserved measurements below. Compile and run headless checks after instrumentation changes. Sustained performance and visual acceptability remain unverified, especially for cave/occlusion and shadow-edge scenes.

## Repeatable baseline procedure (implemented)

Run `tools/renderer-baseline.sh` from the repository root on the target machine, with `JAVA_TOOL_OPTIONS` including `-Dstratadiver.gpuTimers=true -Dstratadiver.scale=1` for the current 1280×720 target. It compiles, performs 120 warm-up frames and records up to 599 timed frames by default (the 600th frame is reserved for capture); `WARMUP_FRAMES`, `FRAMES`, and `CAPTURE` override these values. `FRAMES` must be greater than one. The default is a stationary camera at the same spawn; set `-Dstratadiver.benchmarkTurnDegrees=0.25` for a deterministic continuous turn, applied during warm-up and capture. The run writes a final PNG and reports revision, OS, GL vendor/renderer/version, requested window size, GLFW-reported framebuffer size, actual GL surface size, trace dimensions/divisor, camera-turn setting, CPU-wall median/p95, and GPU pass median/p95. Repeat with the same revision, settings, display configuration, and scene. Cave/occlusion and sunlit/shadow-edge visual cases still need a separate manually recorded playtest; these are not represented as distinct benchmark scenes here.

The current capture mode reads back a PNG on its final frame and that readback synchronizes the GPU; that frame is deliberately excluded from the CPU-wall timing distribution. Per-frame CPU wall timing includes any driver or swap/presentation blocking and therefore is not a pure CPU-work or presentation-wait breakdown. With `-Dstratadiver.gpuTimers=true`, asynchronous `GL_TIME_ELAPSED` queries report trace, resolve, and present-pass GPU work when results become available; they do not include swap/presentation waiting and are not whole-frame GPU time. Without the property, GPU timing is unavailable. Do not interpret the displayed FPS/HUD or CPU wall report as a GPU timing or release gate. Screenshots are saved at the actual GL surface dimensions, not enlarged to a presumed native output.

## Measurement evidence (2026-10-30)

Captured on commit `dd4a93d` with `tools/renderer-baseline.sh`, JDK 23, `-Dstratadiver.gpuTimers=true`, divisor 1, 120 warm-up frames, and 61 loop frames (60 CPU/swap samples; the last loop frame is reserved for the PNG readback). Both stationary runs used the same build, spawn, display, and settings:

| Run | Camera | CPU frame-wall median / p95 | `glfwSwapBuffers` call-wall median / p95 | GPU trace median / p95 | GPU resolve median / p95 | GPU present median / p95 |
|---|---|---:|---:|---:|---:|---:|
| A | stationary | 40.025 / 40.313 ms | 32.721 / 36.267 ms | 34.116 / 36.013 ms | 1.175 / 2.123 ms | 0.054 / 0.068 ms |
| B | stationary repeat | 37.567 / 37.908 ms | 32.680 / 34.192 ms | 32.994 / 33.477 ms | 1.154 / 1.182 ms | 0.042 / 0.057 ms |
| C | turn 0.05° per frame | 37.865 / 38.155 ms | 31.610 / 34.752 ms | 32.372 / 34.028 ms | 1.192 / 1.903 ms | 0.055 / 0.069 ms |

Each GPU distribution has 61 results because it times the render passes of all loop frames, including the final frame whose CPU readback is excluded; GPU pass queries end before that readback. The capture reported macOS 26.6.2, Apple M1 Pro, OpenGL 4.1 Metal (`90.5`), requested/window/framebuffer/GL surface/render dimensions all 1280×720, divisor 1. The display is physically 3840×2160 and macOS reports “Looks like 1920×1080” at 60 Hz. The PNGs are 1280×720 GL-surface captures. App instrumentation cannot establish whether macOS compositor or physical scanout resamples that surface.

The two stationary CPU medians differ by about 6.2%; GPU trace medians differ by about 3.4%, comparable for this short capture. Trace GPU work is the largest measured pass (~33–34 ms), while the swap call occupies ~32–33 ms of ~38–40 ms CPU frame-wall. Swap-call wall can include GPU back-pressure and presentation pacing; it is not a pure display-wait or scanout measurement. The measured stationary rate is roughly 25 FPS, not 60 FPS. This is diagnostic evidence, not a sustained performance guarantee. Captures: `docs/tickets/evidence/renderer-baseline-1280x720.png` (stationary) and `docs/tickets/evidence/renderer-baseline-1280x720-turning.png` (turning); both are actual 1280×720 PNGs.

**Still pending:** measure the later 1920×1080 FHQ target if/when it becomes a release target; run a distinct cave/occlusion scene and explicitly inspect representative shadow edges; and decide whether 1280×720 visual quality is acceptable. No 60 FPS target or performance promise is established by this ticket. GPU timer queries were exercised successfully on this Apple GL driver, but this does not verify other drivers or separate physical scanout from compositor/presentation waiting.
