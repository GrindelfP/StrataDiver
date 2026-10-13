# AGENTS.md

## Project Overview

A from-scratch voxel sandbox game in **Kotlin** using **LWJGL 3** (GLFW + OpenGL) and **JOML** for math. No game engine — everything is implemented manually.

- **Package**: `to.grindelf.stratadiver`
- **Source**: `src/main/kotlin/to/grindelf/stratadiver/`
- **Shaders**: `src/main/resources/shaders/`
- **Build**: Maven (`pom.xml`)

## Hard Rules

1. **No external game engines** — pure Kotlin + LWJGL + OpenGL only.
2. **Always `git pull` before starting work.**
3. **Commit every completed step** and push when something works.
4. **Strict commit prefixes**:
   - `[INIT]` — initial commit
   - `[FEATURE]` — starting a brand-new feature
   - `[UPDATE]` — general updates, refactoring, reorganization
   - `[BUGFIX]` — fixing a bug introduced by previous commits
   - `[DELETE]` — deleting something

## Build & Run

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 23) mvn compile   # compile only
JAVA_HOME=$(/usr/libexec/java_home -v 23) mvn exec:exec # run the game
```

Kotlin 1.9.0 does not support Java 27 (the system default). You **must** use JDK 23 (Azul Zulu) via `JAVA_HOME` or the build fails with `IllegalArgumentException: 27`.

On macOS, GLFW requires `-XstartOnFirstThread`. The `exec:exec` goal forks a new JVM process with this flag. Do **not** use `exec:java` — it runs in the same JVM and GLFW will crash.

There are **no tests** in this project yet.

## Architecture

`StrataDiver` is the composition root: it owns the window and the GL context, orders a
frame, and routes input. Everything with behaviour of its own is its own type.

| File | Purpose |
|------|---------|
| `StrataDiver.kt` | The entry point (`fun main()`, hence JVM class `StrataDiverKt` in `pom.xml`) *and* the game: window/GL lifecycle, frame order, input routing, entity tick + pickup, break/place (class `StrataDiver`, enum `ControlMode`). No separate Main file exists. |
| `ShaderProgram.kt` | Compiles/links the program; resolves every uniform location **once** and sets it by name |
| `Geometry.kt` | `Mesh` (vao+vbo+count, released together), `Geometry.upload()`, and the static shapes `UNIT_QUAD`/`UNIT_CUBE`/`SELECTION_BOX`/`MINIMAP_QUAD` |
| `ChunkStreamer.kt` | Which Chunks exist (`chunks`), which hold GPU memory (`meshed`), block read/write, streaming (interface `SolidWorld`) |
| `Player.kt` | Position/velocity/physics, look, raycast, spawn, `RaycastResult`. Knows nothing about GLFW |
| `UiPass.kt` | Screen-space drawing: the ortho projection, flat Tiles, composited isometric Icons |
| `Hud.kt` | Crosshair, hotbar, inventory, cursor-held item — pure drawing |
| `FpsCounter.kt` | Frame timing + the seven-segment readout (segment names, not bitmasks) |
| `SlotLayout.kt` | Where the slots are on screen, and hit-testing. Pure arithmetic, no GL |
| `World.kt` | `BlockType` enum, `Atlas`, `Chunk` (16×16×16), `MeshGenerator`, `WorldGen`, `PerlinNoise`, `FastNoise` |
| `Entity.kt` | `Entity`, `DroppedItem`, `EntityRenderer`. Depends on `SolidWorld`, not on the game |
| `Inventory.kt` | `ItemStack`, `Inventory` (hotbar + main) |
| `Art.kt` / `AtlasDump.kt` | Authored 16×16 art → atlas buffer; `AtlasDump` renders it to a PNG to check without a window |
| `tools/Check.kt` | Headless assertions over everything above that does not need GL. Not compiled into the game |

### Key Concepts

- **Chunks**: 16×16×16 blocks, stored as `ByteArray`. `Chunk.HEIGHT_LAYERS` = 16 in Y.
- **Two lifetimes**: `chunks` are the world's Cells, created on first touch and never
  discarded; `meshed` is only the subset holding GPU memory. Leaving releases the mesh
  and keeps the Cells. Collapsing the two is what made frame cost grow with distance walked.
- **Terrain**: Perlin noise → height map. Sea level at Y=128. Layers: bedrock → stone → dirt → grass.
- **Meshing**: Face-culling by opacity — a face is drawn unless its neighbour is opaque. Vertex format: 3 pos + 2 UV + 3 normal = **8 floats per vertex**.
- **Textures**: Procedurally generated 256×256 atlas (4×4 tiles of 64px). Block tiles: grass, dirt, stone, bedrock, wood, leaves. UI tiles: slot frame, selected-slot, panel, crosshair, plus `Atlas.LINE` — opaque white, for wireframes and the FPS font, which must survive the alpha cutout.
- **Control routing**: All input (per-frame polling, key callback, mouse move, mouse click) routes through a `ControlMode` — `GAMEPLAY` or `INTERACTIVE`. `INTERACTIVE` is active whenever a GUI layer is open (inventory now, escape menu later): gameplay keys are unassigned, the player is frozen, and mouse clicks drive the UI.
- **GUI**: Flat 2D screen-space quads in an orthographic pass after the 3D world, all through `UiPass`. The hotbar is always-on; the inventory (E) shows a 3×9 grid above it on a Panel. Slots accept clicks (pick up / move / stack via the cursor-held item).
- **Day/night**: `CONST_TIME = true` in the `StrataDiver` companion. Sun/moon direction drives diffuse lighting in the shader.
- **Physics**: AABB collision per-axis (X, Z, then Y). Gravity = −0.015, jump = 0.25. `Player.position` is the **eye**; the body hangs `Player.HEIGHT` below it, so the feet are at `position.y - 1.8`. A resting player stops up to one gravity step short of the surface — that is the per-frame collision, not a bug.
- **Block interaction**: Raycast (step 0.1, reach 5.0) → left click breaks, right click places.
- **Entity**: Dropped items with gravity, friction, spin animation, pickup on proximity.

## Platform-Specific

- **LWJGL natives**: `natives-macos-arm64` (Apple Silicon). If switching platform, change `<lwjgl.natives>` in `pom.xml`.

## Context Log

`CONTEXT.md` is the **domain glossary** (shared vocabulary — keep it free of implementation
detail). Session history lives in `docs/session-log.md`.

### Updating the session log

**Append to `docs/session-log.md` as part of finishing any work, not afterwards as an
afterthought.** One line per entry, format:

```
YYYY-MM-DD, HH:MM, [TAG] What was found, what was changed, and why.
```

- Timestamp with `date "+%Y-%m-%d, %H:%M"`.
- Tag the entry `[INIT]` / `[FEATURE]` / `[UPDATE]` / `[BUGFIX]` / `[DELETE]` to match the commit.
- **Record the diagnosis, not just the change.** Past entries earn their value by naming the
  actual cause — "the SEGMENTS table was hand-written as bitmasks and was wrong for 9 of the
  10 digits" is useful a year later; "fixed FPS counter" is not.
- **Record regressions and the lesson**, including when the mistake was mine. The blue-sky
  regression exists in the log because it teaches more than the fix does.
- Append, never rewrite: the log is a record of what actually happened.

### Verifying changes before declaring them done

Compiling is not verification. This project has no test suite and its rendering path needs a
GL context, so pick the strongest check available and say plainly what it could not cover.

- **`tools/Check.kt` is that check.** It asserts the real numbers behind the things that have
  actually broken before: mesher vertex counts and all-air Chunk counts, generation
  determinism, slot hit-testing, the seven-segment table, physics and raycast against a fake
  world. Run it after any change to those:

  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 23)   # Kotlin 1.9.0 cannot parse JDK 27
  mvn -q compile
  CP=~/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib/1.9.0/kotlin-stdlib-1.9.0.jar:~/.m2/repository/org/joml/joml/1.10.5/joml-1.10.5.jar:~/.m2/repository/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar:target/classes
  kotlinc tools/Check.kt -cp "$CP" -d /tmp/checkout && java -cp "/tmp/checkout:$CP" check.CheckKt
  ```

  Use `export`, not the `JAVA_HOME=... mvn` prefix form, when chaining commands — the
  prefix does not survive into a later `&&` branch and the build dies on `IllegalArgumentException: 27`.

  It lives in `tools/`, not `src/main/kotlin`, so Maven never compiles it into the game.
  Add to it when you touch something that can be checked this way — the alternative is
  discovering the regression by playing.
- **More things are checkable than used to be.** `SolidWorld` exists so `Player` and `Entity`
  can be driven against a fake world with no GL at all; `SlotLayout` is pure arithmetic. If
  you find yourself reasoning about behaviour you cannot check, the fix is usually to widen a
  seam rather than to squint harder.
- **Anything touching GL** still cannot be verified without the window. Say so, and ask the
  user to run it. Do not let "it compiles" stand in for "it works" — that is how a change
  shipped that rendered the entire world as sky, and how another shipped after "compiles"
  was the only evidence offered.
- **Localise before fixing.** When something breaks, use what still works to bound the fault:
  if physics and raycasting are fine but nothing renders, the data is fine and the fault is in
  the draw path.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (uses `gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five canonical triage labels. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at repo root. See `docs/agents/domain.md`.
