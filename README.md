# Kubold

A from-scratch voxel sandbox game written in **Kotlin** using **LWJGL 3** (GLFW + OpenGL) and **JOML** for math. No game engine — everything is implemented manually.

This game is inspired by Minecraft and is strictly an educational experiment. It is not affiliated with Mojang AB or Microsoft.

## Features

- Procedural terrain generation using Perlin noise
- Chunk-based world (16×16×16 blocks, face-culling mesh generation)
- Procedurally generated texture atlas
- Day/night cycle with dynamic lighting
- Physics: gravity, AABB collision, jumping
- Block breaking/placing via raycasting
- Inventory system with hotbar
- Dropped item entities with pickup
- Trees

## Build & Run

**Prerequisites**: macOS with Apple Silicon, JDK 17+, and Maven installed.

```bash
mvn compile
mvn exec:java
```

To compile without running:
```bash
mvn compile
```

## Controls

| Key | Action |
|-----|--------|
| W / A / S / D | Move |
| Mouse | Look around |
| Space | Jump |
| Left Click | Break block |
| Right Click | Place block |
| E | Toggle inventory |
| 1–9 | Select hotbar slot |
| Escape | Release mouse / close window |

## Project Structure

```
src/main/kotlin/to/grindelf/minecraft/
├── Main.kt        # Entry point, game loop, rendering, input, physics
├── World.kt       # BlockType, Chunk, MeshGenerator, PerlinNoise
├── Entity.kt      # Entity base class, DroppedItem
├── Inventory.kt   # ItemStack, Inventory
└── Utills.kt      # RaycastResult
src/main/resources/shaders/
├── vertex.glsl
└── fragment.glsl
```

## Development

### Build & Run

```bash
mvn compile          # Compile Kotlin sources
mvn exec:java        # Run the game (add exec-maven-plugin first)
```

### Git Workflow

1. `git pull` before starting work
2. Make changes, `mvn compile` to verify
3. Commit with a descriptive message
4. `git push`

Commit messages follow a strict prefix format:

| Prefix | Purpose |
|--------|---------|
| `[INIT]` | Initial commit |
| `[FEATURE]` | Starting a brand-new feature |
| `[UPDATE]` | General updates, refactoring, reorganization |
| `[BUGFIX]` | Fixing a bug introduced by previous commits |
| `[DELETE]` | Deleting something |

### Creating a New Release

1. Bump `<version>` in `pom.xml`
2. Tag the commit: `git tag v<version>`
3. Push the tag: `git push --tags`

### Contributing

1. Create a new branch from `main`
2. Make changes following the coding style
3. Ensure `mvn compile` passes
4. Open a pull request with a clear description

## License

This project is licensed under a **non-commercial license**. See [LICENSE](LICENSE) for details. You may not use this software or any derivative works for commercial purposes.
