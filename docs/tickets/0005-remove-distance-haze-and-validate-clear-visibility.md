# 0005 — Remove unwanted distance haze and validate visibility

Type: Visual quality<br>
Status: Done<br>
Depends on: 0002, 0004

## Problem

The stated goal is a clean, sharp image without fog. `trace.frag` applies aerial perspective with `FOG_DENSITY = 0.002`; transmittance is `exp(-0.002 * distance)`, so at 100 cells only about 81.9% of the original radiance remains before sky haze is mixed in. That is visible haze, not just a volume-edge guard. The volume documentation currently relies on haze to conceal its edge, so removing it requires correct volume bounds and missing-chunk semantics first.

## Steps

1. Separate intentional atmosphere from the volume-edge fallback. Make the aesthetic default clear/no-fog; if atmosphere is desired later, expose it as an explicit setting with a documented range.
2. Ensure rays outside the uploaded window and through missing chunks behave consistently as open air; do not hide a hard volume edge by fogging the whole world.
3. Verify sunlight/sky visibility and cave darkness with controlled scenes before and after disabling haze.
4. Check distant terrain and the world-volume boundary while walking; if a boundary appears, fix coverage/streaming rather than restoring global fog.

## Acceptance

- Default image has no distance-dependent haze in ordinary outdoor scenes.
- No visible hard edge, stale terrain, or sky discontinuity appears at the streamed-volume boundary.
- Sealed spaces remain dark and open sky remains lit under the reference cases.

## Verification and remaining checks

Shader validation and CPU checks can cover portions of the logic. **Real GL playtesting while walking and turning is mandatory** to detect the volume edge, streaming discontinuities, and visual haze. Those observations remain outstanding until playtested.
