# 0008 — Optimize GPU traversal from measured bottlenecks

**Type:** Performance
**Status: ready/done** — partial; GPU timer instrumentation added, profiling and optimization blocked on real-window measurements
**Depends on:** 0001–0007

## Problem

Path-tracing cost depends on pixel count, cell traversal, bounce count, and light samples. The current GL 4.1 fragment path is constrained by macOS's OpenGL ceiling; compute shaders (GL 4.3) are not a portable option for this target. No traversal rewrite should precede correctness and measured profiling. The existing playtest/JFR evidence is not a GPU profile and must not be used to infer shader bottlenecks.

## Steps

1. Use ticket 0001's measurements to identify whether trace, denoise, temporal, upload, or presentation dominates at each resolution.
2. Add GPU timer-query profiling around passes where supported; retain a clear fallback and disclose any unresolved GPU timing.
3. Use shader counters/debug variants or controlled scenes to measure traversal steps, hit distance, bounce count, and light-sample cost without materially changing release behavior.
4. Optimize one demonstrated bottleneck at a time (for example, DDA bounds/early exits or redundant uniform uploads). Keep the CPU tracer and deterministic fixtures as reference.
5. Re-run quality and performance gates after each optimization. Consider an alternate graphics API only if measured GL limits block the agreed target and a separately reviewed migration plan is approved.

## Acceptance

- Every optimization has before/after measurements on the same scene, render size, hardware, and build configuration.
- Reference-scene output remains within defined tolerances; no optimization silently changes lighting semantics.
- OpenGL 4.1 remains the baseline; no compute-4.3 dependency is introduced for macOS.
- Any alternate API proposal is evidence-based and explicitly scoped, not bundled into routine tuning.

## Verification and remaining checks

Offline shader validation and CPU fixtures do not measure GPU performance. **Real GL-window profiling and visual playtesting are mandatory**. A locked-60 claim remains unverified unless the confirmed target resolution sustains the agreed frame-time percentile under both stationary and moving playtests.

## Partial implementation (2026-10-30)

`GpuPassTimers` adds opt-in (`-Dstratadiver.gpuTimers=true`) OpenGL elapsed-time queries around the trace draw, resolve group (accumulation + optional denoise + temporal EMA), and presentation. It uses a ring of query objects, checks `GL_QUERY_RESULT_AVAILABLE`, and skips a sample if its slot is still in flight; it never waits for a result. `capture` prints available GPU timings alongside CPU wall timings. The grouping does not isolate individual resolve subpasses. No shader traversal counters were added: those require changing shader behavior and should follow a demonstrated need.

No real-window GPU measurements are available from this environment. Accordingly traversal is unchanged, no bottleneck is claimed, and optimization / acceptance remain blocked pending repeated captures on the target GPU at the same scene and render size, followed by stationary and moving visual playtests. Do not infer GPU cost from CPU frame-wall numbers or claim 60 fps from these tools alone.

For a GPU-capable local run, use the existing capture path with a warmup and enough measured frames, passing `-Dstratadiver.gpuTimers=true`; record revision, OS/GPU/OpenGL metadata, window/framebuffer/render dimensions and both timing reports. Query availability can lag capture completion, so the report's sample count matters. Compare identical configurations; CPU wall can include driver/presentation blocking and GPU query scopes measure GPU elapsed time, not the CPU submission cost.
