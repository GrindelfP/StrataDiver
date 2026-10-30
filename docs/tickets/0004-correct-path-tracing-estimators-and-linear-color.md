# 0004 — Audit path-tracing estimators and color-space handling

**Type:** Rendering correctness
**Status:** Done
**Depends on:** 0001, 0002

## Problem

The GPU trace shader is now the rendered path, but key sampling choices need correctness checks before tuning brightness or denoising. `trace.frag` applies Russian roulette termination after updating throughput without compensating surviving paths by the survival probability. It also skips some direct sky evaluation at deeper bounces while escaping rays still receive a fixed `SKY_MIS_WEIGHT`; establish whether the resulting estimator is biased and correct it rather than preserving a comment/constant by assumption. The shader samples authored atlas texels into lighting; verify the texture's sRGB/linear treatment and keep lighting/filtering in linear radiance.

## Steps

1. Document the intended estimator and MIS probabilities for direct Sun/Moon, sampled sky, and escaped bounce rays. Compare GPU logic to the CPU tracer rather than assuming comments guarantee parity.
2. Make Russian roulette unbiased: divide surviving throughput by the survival probability, with safe bounds; add deterministic statistical/reference checks where possible.
3. Reconcile the deep-bounce sky skip with the escape contribution and MIS weights. Remove the skip, or derive a valid weighting scheme and demonstrate agreement with the reference.
4. Define the atlas input color space and output pipeline. Decode authored sRGB colors to linear before material lighting if appropriate; accumulate/filter linear radiance and apply display transform once at presentation.
5. Compare deterministic debug scenes and many-seed averages against the CPU oracle, documenting expected variance and tolerances.

## Acceptance

- Roulette does not introduce a systematic energy loss in reference scenes.
- Sky contribution is consistent between sampled direct lighting and escaped bounces; no unexplained fixed weighting remains.
- Color-space handling is explicit and avoids applying gamma twice or filtering display-encoded color.
- CPU/GPU comparisons on supported deterministic cases pass stated tolerances; multi-seed means show no unexplained bias.

## Verification and remaining checks

Headless CPU checks and offline shader validation are useful but cannot prove the active GPU shader's visual correctness. **A real GL playtest** must inspect bright/dark transitions, caves, shadows, and authored block colors. Statistical convergence and visual look remain unverified until run.
