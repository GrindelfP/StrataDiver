# StrataDiver

A voxel sandbox game built from scratch in Kotlin with raw OpenGL. This file is the shared
vocabulary of the domain: the words we use, and what each one means.

## Language

### World

**Block Type**:
The complete set of things that can occupy a single cell of the world, each with its own
identity, appearance and behaviour. Every Block Type is also what you hold and place.
_Avoid_: Block, tile, item
*Note*: `AIR` is a Block Type — absence of a block is itself a Block Type.

**Cell**:
One unit cube of world space, identified by its integer position.
_Avoid_: Block (ambiguous between the cell and its Block Type)

**Chunk**:
A fixed-size cube of Cells generated and meshed as a unit.
_Avoid_: Section, region

**Column**:
A vertical line of Cells through the world at one horizontal position, from bedrock upward.
_Terrain layer_: one of the material bands in a Column (bedrock, stone, dirt, grass).

**Surface**:
The topmost non-air Cell in a Column, found by scanning down from the ceiling of the
world. A Column always has a Surface: where the ground is solid, it is the ground; where
a player has dug down, it is the new floor. The Minimap shows Surfaces.
_Avoid_: height, ground level, top block, terrain height

### Appearance

**Atlas**:
The single image holding every Tile the game draws, arranged in a grid.
_Avoid_: Sprite sheet, texture pack

**Tile**:
One square region of the Atlas that a Block Type is drawn from. A Block Type may use a
different Tile per face.
_Avoid_: Texture, sprite

**Art**:
The hand-tuned source pixels of a Tile, authored at low resolution for a deliberately
chunky, retro look. Art is *not* what ships: it is enlarged to fill a Tile.
_Avoid_: Texture, pixels

**Cutout**:
A Tile with fully transparent pixels punched through it, so whatever is behind shows
through. Cutouts read as holes; they are not see-through glass.
_Avoid_: Transparency, alpha blend

**Icon**:
The isometric, three-faced picture of a Block Type shown in inventory and hotbar slots.
An Icon is assembled from the Block Type's own Tiles at draw time — it is not an
independent piece of art.
_Avoid_: Item icon, sprite

**Silhouette**:
The overall shape a form reads as from a distance. Blocks in this world are judged partly
on their Silhouette, not only on their surface.
_Avoid_: Shape, outline

### Interaction

**Drop**:
The item released into the world when a Block is destroyed, falling until collected.
Most Block Types Drop themselves. Some — leaves, chiefly — Drop nothing, so that
destroying them cannot fill the inventory.
_Avoid_: Item drop, pickup

**Solid**:
Whether a Block Type obstructs movement through the cells it occupies.
_Avoid_: Hard, collidable

**Opaque**:
Whether a Block Type hides the faces of its neighbours. Cutouts are never Opaque, even
though they are Solid: you can stand on them and see straight through them.
_Avoid_: Solid (a different question)

**Panel**:
The surface behind a GUI layer, chosen to contrast with the slot contents drawn on it.
_Avoid_: Background, GUI background

**Minimap**:
The round, north-up plan view of the Surfaces around the player, always in the top-right
corner. It is derived live from the world's Cells — never from a cached image of them —
so a structure the player builds appears under the same rules as natural terrain.
_Avoid_: Map, radar, overview, satellite view

**Unknown**:
A place on the Minimap whose Cell the world has not yet produced. Distinct from a Column
that is genuinely empty: empty means there is nothing there, Unknown means we do not know.
The two are never drawn alike.
_Avoid_: Unloaded, fog, void
