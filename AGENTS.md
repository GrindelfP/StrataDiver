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

| File | Purpose |
|------|---------|
| `Main.kt` | Entry point (`fun main()`), game loop, rendering, input, physics, camera, block interaction (class `StrataDiver`) |
| `World.kt` | `BlockType` enum, `Chunk` class (16×16×16), `MeshGenerator`, `PerlinNoise`, `FastNoise` |
| `Entity.kt` | `Entity` abstract class, `DroppedItem` |
| `Inventory.kt` | `ItemStack`, `Inventory` (hotbar + main) |
| `Utills.kt` | `RaycastResult` data class |

### Key Concepts

- **Chunks**: 16×16×16 blocks, stored as `ByteArray`. World is a `Map<Triple<Int,Int,Int>, Chunk>`. Vertical stacking: 16 chunks in Y.
- **Terrain**: Perlin noise → height map. Sea level at Y=128. Layers: bedrock → stone → dirt → grass.
- **Meshing**: Face-culling — only faces adjacent to AIR are emitted. Vertex format: 3 pos + 2 UV + 3 normal = **8 floats per vertex**.
- **Textures**: Procedurally generated 256×256 atlas (4×4 tiles of 64px). Block tiles: grass, dirt, stone, bedrock, wood. UI tiles: slot frame (beveled), selected-slot (white frame), panel background.
- **Control routing**: All input (per-frame polling, key callback, mouse move, mouse click) routes through a `ControlMode` — `GAMEPLAY` or `INTERACTIVE`. `INTERACTIVE` is active whenever a GUI layer is open (inventory now, escape menu later): gameplay keys are unassigned, the player is frozen, and mouse clicks drive the UI.
- **GUI**: Rendered as flat 2D screen-space quads in a separate orthographic pass after the 3D world. The hotbar is always-on at the bottom; opening the inventory (E) shows a 3×9 grid above it on a panel. Slots accept mouse clicks (pick up / move / stack via the cursor-held item).
- **Day/night**: `CONST_TIME = true` fixed in `Main.kt` companion. Sun/moon direction drives diffuse lighting in shader.
- **Physics**: AABB collision per-axis (X, Z, then Y). Gravity = −0.015, jump = 0.25.
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

- **Anything not touching GL** can be exercised headlessly. `Chunk`'s constructor and
  `MeshGenerator` make no GL calls, so they can be driven from a scratch main class on
  `target/classes` to assert real numbers (vertex counts, determinism across regeneration).
- **Anything touching GL** cannot be verified without the window. Say so, and ask the user to
  run it. Do not let "it compiles" stand in for "it works" — that is how a change shipped that
  rendered the entire world as sky.
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
