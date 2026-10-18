# StrataDiver — from 20 fps to a GPU

Status: agreed, not started. Every decision below was settled by grilling; the reasoning
behind each is one line, and the line is load-bearing.

## What is wrong, with the code that does it

Five reports from the first playtest of the path tracer. Four have an identified cause.

**1. The GUI is not path traced — it is being handed the traced image as its texture.**
`TracedImage.draw()` binds its picture texture to `GL_TEXTURE0` (`TracedImage.kt:117`) and
never gives unit 0 back. Every HUD quad drawn afterwards samples `textureAtlas`, which the
shader resolves to unit 0. So the Atlas uniform points at the traced picture.

The symptom shape confirms it beyond doubt. The crosshair is a 20×20 quad showing a 20×20
patch of the scene — "a small window". A hotbar icon is three skewed parallelograms each
showing a skewed patch — "a set of minicameras". The FPS digits are drawn from `Atlas.LINE`
as **solid rectangles**, so the shape is geometry and cannot change while the colour is
whatever the converging picture is behind them — "changing appearance and color, not
shapes". And the Minimap, the one HUD element that binds its own texture (unit 1,
`UiPass.kt:151`), is the one element nobody complained about. `UiPass.kt:149` carries a
comment asserting unit 0 still holds the Atlas: true until 2026-10-18, false since.

**2/4. Performance.** Four costs, and the budget controller cannot see three of them.

- `PathTracer.rgba()` (`PathTracer.kt:990`) tonemaps the whole image on the main thread,
  single-threaded: 3 `exp` + 3 `pow` per pixel and a fresh `ByteArray` every frame. At
  640×360 that is ~1.4M transcendental calls before a worker thread starts. It sits
  **outside** the measured `traceMillis` (`StrataDiver.kt:622-624`), so the controller is
  blind to it.
- The adaptive scaler moves one notch every 24 frames (`SCALE_DECISION_FRAMES`,
  `StrataDiver.kt:121`). At 3–4 fps that is 7 s per notch and six notches — the reported
  "1–2 minutes before it improves" is not the machine warming up, it is a controller with a
  45-second time constant.
- The meshing pipeline runs anyway. Every frame `ChunkStreamer.update()`
  (`ChunkStreamer.kt:253-310`) builds a ~567-entry `HashSet<Triple>`, filters and sorts it,
  then lights and meshes up to 8 Chunks with 11-float vertices and neighbour face culling.
  The tracer reads no vertex. `setBlock` re-meshes up to 7 neighbours per break.
- ~5 µs per pixel-sample ≈ 15,000 cycles. The target for 60 fps at 1280×720 is 55.3M
  samples/s ≈ 430 cycles. Micro-optimisation is a 3–5× lever and bottoms out near 2,000.

**3. The blur** is two stacked defects: `PATH_SCALE_LIMIT = 8` floors the image at 160×90
(`StrataDiver.kt:118`), and there is one sample per pixel per frame with no denoiser.

**5. The picture is good** and nothing below touches it. The soft shadow edges and the colour
bleed come from the Sun-as-area-light and the MIS split. Every fix here is outside that path.

## What is being built

**Move the tracer to the GPU. OpenGL 4.1, fragment-shader ping-pong, one flat 3D world
texture.** Identical physics to the CPU tracer, which is kept as a test oracle.

- **API: OpenGL 4.1 fragment shaders.** macOS caps OpenGL at 4.1 core — compute shaders
  (4.3) and image load/store (4.2) do not exist on Apple's driver. A voxel tracer at
  720p60 is not ALU-starved; it needs the rays off the CPU, and GL 4.1 gets them there.
  The identical shader runs on Windows and Linux tomorrow.
  **Fallback, with a trigger:** if the finished GL path still plateaus below 30 fps at
  native 1280×720 with temporal and denoise in place, MoltenVK/Vulkan goes on the table
  with measurements behind it.
- **Accumulation: ping-pong.** Two RGBA32F textures; each frame one fullscreen pass reads
  the previous average and the new sample and writes `(old + new)/n`. Multiple render
  targets carry radiance, albedo, normal and depth from the same pass.
- **World storage: one flat 3D `R8` texture** over the streamed window (~144×144×112,
  ≈2.3 MB). Chunk generation is a sub-image upload at a computed offset; a block break is a
  one-texel upload. A ray is `floor(pos - windowOrigin)` and one fetch — no per-ray hash
  lookup, which on the GPU is worth more than tidier memory organisation.
- **Physics: identical.** Same Sun disc, MIS weights, Russian roulette, cutout handling,
  aerial-perspective gate, firefly clamp. If the GPU version looks different from the CPU
  version, one of them has a bug and the oracle says which.
- **Verification: the CPU tracer stays, demoted to a fixture.** `Check.kt` asserts the two
  agree on the closed-form cases it already has — 1296 ray directions against exact box
  intersection, 40 leaf holes against the art's own alpha, a roofed room at 0.0 with a
  sunbeam at 0.172. None of those need a GPU; they need *an* implementation. Two independent
  implementations agreeing is a stronger claim than either alone.
- **Denoise: guided, in a separate stage after the accumulation.** The tracer already knows
  albedo, normal and depth, so the filter smooths within a surface without smearing the
  shadow edges. The raw averaged radiance stays untouched and inspectable.
- **Temporal: reproject the previous frame through the camera delta** so a moving picture
  gets many samples per pixel instead of one. Second, after the tracer is fast.
- **Degradation order: resolution first**, floor at half. A soft image is the failure
  already named as tolerable; darker rooms and a zooming lens are not.

## Ordered steps

### 0. Capability probe — *do this first, it is 20 minutes and everything rests on it*

Print `GL_VERSION`, `GL_RENDERER`, `GL_VENDOR`, and the presence of
`GL_ARB_compute_shader`, `GL_EXT_color_buffer_float`, `GL_ARB_timer_query`, `GL_MAX_3D_TEXTURE_SIZE`.

Why: the whole GPU design rests on the 4.1 ceiling. If that is wrong, N1 and N2 have a
strictly better shape available (compute shader, BVH in textures) and I would rather know on
day one than in three days. The fragment design is correct either way, so nothing else is
blocked while this runs.

### 1. GUI texture-unit ownership — `[BUGFIX]`

`TracedImage` binds its picture to its own unit; `UiPass.begin()` binds the Atlas to unit 0
explicitly instead of assuming the world pass left it there. Texture-unit ownership becomes
a property of the pass that owns it.

Verify: by eye, immediately — the crosshair is a cross again, the hotbar icons are icons,
the FPS digits stop changing colour. Then `glslangValidator` on anything touched.

Cost: a few lines. Highest confidence item in the plan.

### 2. Delete the meshing pipeline and the rasterizer — `[DELETE]`

`MeshGenerator`, `Geometry.POS_UV_NORMAL_LIGHT`, `Chunk.mesh`, the mesh lifecycle in
`ChunkStreamer`, the per-frame `HashSet<Triple>`/filter/sort in `update()`, the neighbour
re-meshing in `setBlock`, `drawRasterWorld`, `drawWorld`, `drawSelection`, `itemCube`,
`selectionBox`, and the `F` toggle.

Once meshing stops, the tracer reads no vertex, no per-vertex light and no GPU geometry.
This also frees the CPU the GPU port wants.

Verify: `tools/Check.kt` — the mesher assertions go with the mesher; the 1,213,368-vertex
figure is only meaningful while it exists. Everything else green. Frame breakdown (step 3)
shows `stream` collapsing to near zero, which is the confirmation the deleted work was
really the deleted work.

> **One unresolved micro-decision.** `F` goes with this. Keeping it costs a rasterizer that
> has nothing to draw. My default is to delete both; if you want the old look preserved for
> reference, say so now and it stays behind the boolean for one session. One line to change
> either way.

### 3. Frame-time breakdown overlay — `[FEATURE]`

Replaces the bare FPS number: **trace / tonemap+upload / stream+mesh / present**, each a
measured millisecond count, plus samples accumulated and the current resolution divisor.

Why now, before the GPU port: the `rgba()` tonemap turned out to be invisible to the budget
controller, so no estimate about where the milliseconds go has been trustworthy — including
mine. Four measured numbers localise the fault before anything is optimised, which is the
lesson this log has already paid for twice. It survives the plan as the headline number of
a renderer whose whole selling point is that it is expensive.

GPU cost needs a fence (`glFenceSync` + `glClientWaitSync`); `ARB_timer_query` if the probe
found it, the fence if not.

Verify: by eye, and the four numbers are the acceptance test for step 0's answer.

### 4. Session log, commit, push — before the port

Per the project's rule: append to `docs/session-log.md` with the diagnosis, not the change,
including the texture-unit bug's actual cause and the "it was not the machine, it was the
controller's time constant" finding. Then commit and push the simple fixes as their own
commits, so the GPU port's diff is the port.

### 5. The GPU tracer

In this order, each separately checkable:

**5a — World texture.** The flat 3D `R8` texture over the streamed window; incremental
sub-image upload on chunk generation, one-texel upload on a block break. This replaces
meshing as the GPU data path. Verify by dumping the texture to a PNG and looking at it.

**5b — Trace shader.** Fullscreen fragment pass: DDA over the 3D texture, the physics of
`PathTracer.trace()` ported unchanged, writing radiance + G-buffer through MRT. Ping-pong
accumulation. `glslangValidator` after every edit — it catches undeclared uniforms, which
on GL is a silent no-op rather than an error.

**5c — Present.** Tone map and gamma move from `rgba()` onto the GPU. This deliberately
breaks `TracedImage`'s own stated promise to "draw what it is given", because tonemapping
921k pixels on the CPU main thread is now a measured frame cost. The promise is kept in the
form that matters: **the raw averaged radiance stays untouched and readable**, and the film
is a separate stage after it, so `Check.kt` still sees the unfiltered picture.

**5d — Resolution and sample controller.** Target 16.6 ms. React in ~6 frames, not 24.
Degrade resolution first, floor at half. Measure everything the budget cares about —
including the stages that are currently outside the measured window.

**5e — Oracle harness.** A hidden-window GL context (`GLFW_VISIBLE = false`) in
`tools/Check.kt`: render the GPU tracer against a world `Check.kt` builds, read back, and
assert agreement with the CPU tracer on the closed-form cases. A GPU perf gate needs this
harness anyway, so the two are the same work.

Verify: by eye, against the CPU tracer's picture side by side. This step is the one with
the most unverified surface and the least forgiving failure mode — a GLSL mistake produces a
plausible wrong image, not an error.

### 6. Guided denoiser and sharpen — `[FEATURE]`

Second program between accumulation and presentation: an à-trous / bilateral filter guided by
the albedo, normal and depth MRT already carries, then an unsharp mask at the upscale. Fixes
both halves of the blur complaint at once — the grain and the fogginess.

Verify: by eye. Numerically, Check.kt can assert that a flat, directly-lit surface's filtered
value stays within a stated bound of its unfiltered mean, which is the property a denoiser
must not violate.

### 7. Temporal accumulation — `[FEATURE]`

History buffer, reprojection through the camera delta, disocclusion handling. The last step
because it is the one that most rewards having a fast tracer underneath it, and the one that
would be impossible to attribute if it were built first.

Verify: by eye, and a numerical check that reprojection leaves a static scene's converged
value unchanged when the camera does not move.

### 8. The LightEngine question, reopened — *deliberately deferred*

Once meshing is gone, the tracer uses light for exactly one thing: a boolean per hit, "can
this Cell see any sky?", gating the distance haze. The tracer already knows the answer
statistically and for free — the fraction of cosine-weighted sky samples that escape *is*
the sky visibility, and using it instead of a stored byte is more correct.

Not scheduled. It changes a visual property (soft gate instead of binary, so some of the
crisp cave-versus-valley distinction) that has not yet been seen without the mesher running.
Bundling a subsystem deletion into a performance change is how one hides the other.

## The fallback, stated once so it is a decision and not a drift

If step 7 completes and native 1280×720 still plateaus below 30 fps: MoltenVK via
`lwjgl-vulkan`. It is a real path and it is a large one — swapchain, pipelines, descriptor
sets, allocation, barriers, none of it checkable offline — and it inserts a translation
layer rather than removing one. It gets chosen only if the measurements say the GL 4.1 path
has run out of room. `AGENTS.md` Hard Rule 1 currently reads "pure Kotlin + LWJGL +
**OpenGL** only"; its intent is *no game engines*, so the rule is amended honestly if this
is ever taken, rather than stretched quietly.

## Not being touched

- **The tracer's physics.** Identical, deliberately, so the two implementations are
  comparable.
- **The CPU tracer.** Demoted to a fixture, not deleted. Its ~40 assertions are the only
  verification this renderer has that does not need a window.
- **Audio, inventory, minimap, player physics, world generation.** Nothing in this plan
  touches them.
- **`CONTEXT.md`** gets one addition, when the terms settle: a **Sample** (one ray path folded
  into the average) and the distinction between the raw radiance and the presented image.
  The glossary is shared vocabulary; both are already used constantly and neither is written
  down.

## What stays unverifiable without a window

Named in advance, per the project's rule that "it compiles" is not verification:

- Whether the GUI fix actually fixes the GUI — one look.
- Whether the deleted work was really the deleted work — the step 3 numbers.
- **The entire GPU port.** Steps 5, 6 and 7 have no offline check except `glslangValidator`
  and the oracle numbers, and the oracle only covers the closed-form cases. A GLSL mistake
  produces a plausible wrong image. The playtest is not a formality here; it is the only
  verification these steps have.

## Open item

Step 2's `F` question, above. Everything else is settled.