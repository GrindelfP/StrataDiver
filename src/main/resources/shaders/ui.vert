#version 330 core

// The GUI pass. Every quad in this file is a screen-space rectangle: the hotbar, the
// inventory, the cursor-held item, the Minimap, the crosshair and the FPS readout.
//
// There is no world here any more, and that is not a simplification — it is what is true.
// The 3D pass used to share this shader with the rasterizer, which is why it carried a
// normal, two light channels and a corner shade that a GUI quad has no meaning for. The
// path tracer produces the world as an image and never draws geometry, so those attributes
// have no reader left. They were removed rather than left switched off, because an attribute
// a shader still declares is one more thing that can be wrong without ever being wrong
// visibly.

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aTexCoord;

out vec2 TexCoord;

// Only the Minimap reads this. It is set to (-1,-1) for everything else, and the fragment
// shader uses that as its "not the minimap" test — a sentinel rather than a second draw
// mode, so the ordinary GUI path is untouched by the Minimap.
out vec2 MapLocal;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

// Atlas art rather than a Minimap texture, so the quad's 0..1 UVs are offset into the Tile
// the uniform names. The Minimap draws with this off, because its UVs are map coordinates
// and must not be divided by the atlas size.
uniform bool isEntity;
uniform vec2 uvOffset;

uniform bool isMinimap;

void main() {
    if (isEntity) {
        // Map 0..1 onto the specific Tile quadrant.
        TexCoord = (aTexCoord + uvOffset) / 4.0;
    } else {
        TexCoord = aTexCoord;
    }

    if (isMinimap) {
        // The Minimap quad already carries 0..1 UVs, so they are its map coordinates
        // directly.
        MapLocal = aPos.xy + 0.5;
    } else {
        MapLocal = vec2(-1.0);
    }

    gl_Position = projection * view * model * vec4(aPos, 1.0);
}