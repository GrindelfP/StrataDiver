# AGENTS.md

## Project Overview

A from-scratch voxel sandbox game in **Kotlin** using **LWJGL 3** (GLFW + OpenGL) and **JOML** for math. No game engine — everything is implemented manually.

- **Package**: `to.grindelf.minecraft`
- **Source**: `src/main/kotlin/to/grindelf/minecraft/`
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
| `Main.kt` | Entry point (`fun main()`), game loop, rendering, input, physics, camera, block interaction (class `Kubold`) |
| `World.kt` | `BlockType` enum, `Chunk` class (16×16×16), `MeshGenerator`, `PerlinNoise`, `FastNoise` |
| `Entity.kt` | `Entity` abstract class, `DroppedItem` |
| `Inventory.kt` | `ItemStack`, `Inventory` (hotbar + main) |
| `Utills.kt` | `RaycastResult` data class |

### Key Concepts

- **Chunks**: 16×16×16 blocks, stored as `ByteArray`. World is a `Map<Triple<Int,Int,Int>, Chunk>`. Vertical stacking: 16 chunks in Y.
- **Terrain**: Perlin noise → height map. Sea level at Y=128. Layers: bedrock → stone → dirt → grass.
- **Meshing**: Face-culling — only faces adjacent to AIR are emitted. Vertex format: 3 pos + 2 UV + 3 normal = **8 floats per vertex**.
- **Textures**: Procedurally generated 256×256 atlas (4×4 tiles of 64px). Tiles: grass, dirt, stone, bedrock, wood.
- **Day/night**: `CONST_TIME = true` fixed in `Main.kt` companion. Sun/moon direction drives diffuse lighting in shader.
- **Physics**: AABB collision per-axis (X, Z, then Y). Gravity = −0.015, jump = 0.25.
- **Block interaction**: Raycast (step 0.1, reach 5.0) → left click breaks, right click places.
- **Entity**: Dropped items with gravity, friction, spin animation, pickup on proximity.

## Platform-Specific

- **LWJGL natives**: `natives-macos-arm64` (Apple Silicon). If switching platform, change `<lwjgl.natives>` in `pom.xml`.

## Context Log

See `CONTEXT.md` for session history.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (uses `gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five canonical triage labels. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at repo root. See `docs/agents/domain.md`.
