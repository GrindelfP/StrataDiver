# 1. The Minimap is a CPU-filled texture, not a shader computation

Date: 2026-10-13
Status: accepted

## Context

We want a round, top-right minimap showing the topmost non-air Cell of each Column
around the player, live — so a structure the player builds appears on the map under
exactly the same rules as natural terrain.

There is already a minimap in the repository. It was written, switched off when the GUI
moved to screen space, and left parked. Reading it before designing anything was the
right move: it is a `isMap` branch in `fragment.glsl` that computes
`sin(worldCoord.x * 0.1) * cos(worldCoord.y * 0.1)` and paints the result green or grey.
It has never read a single block of the world.

## Decision

The map data is produced on the CPU and reaches the GPU as a 64×64 RGBA texture, one
pixel per Cell, uploaded with `glTexSubImage2D` and sampled by the fragment shader.

The shader keeps what it is good at and already does: the circular clip, the rim, and
the player marker are per-pixel work and belong there. What it cannot do is decide *what
is at a world position*, because a fragment shader has no access to world data at all.
That is not a limitation to work around within the shader; it is the reason the previous
attempt produced plausible-looking nonsense.

Two supporting decisions:

- **Nearest sampling, not linear.** 64 texture pixels across a 208px disc means linear
  filtering would interpolate between Cells that do not touch, producing a map that
  shows terrain which does not exist. At 2.25 screen pixels per Cell, blur is a lie.
- **Top-down scan from the world ceiling, cached per Chunk.** Each Chunk lazily computes
  and caches the topmost non-air Cell per column, invalidated whenever a Cell is
  written. Without the cache a full 64×64 rebuild walks chunk layers repeatedly for
  nothing; with it, a rebuild is a few tens of microseconds.

## Consequences

- Adding a new Block Type means adding one colour to the minimap palette. Nothing else.
- The minimap cannot be wrong about the world by construction — every pixel came from a
  `Chunk` that exists, or is explicitly drawn as unknown.
- A `BlockType` colour now exists in the codebase, keyed by how the type reads from
  above. It is kept in the minimap rather than on `BlockType` itself, because it is a
  rendering concern and `BlockType` is about identity.
- The parked `isMap` branch is deleted rather than adapted. It encoded an approach we
  have now established cannot work.

## Alternatives considered

- **Compute the surface in the fragment shader** from the player's position. Impossible
  without world access; this is precisely the mistake the parked branch made.
- **Recompute terrain from Perlin noise for the minimap.** Cheapest possible, and would
  show terrain with no memory cost at all — but it cannot show anything the player built,
  because a built structure lives in `Chunk` data and not in the noise function. It fails
  the requirement directly.
- **A higher-resolution map texture** with per-pixel smoothing. Spends texture memory on
  redundancy and trades honest blocky Cells for a blurred impression of terrain.
