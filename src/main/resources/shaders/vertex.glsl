#version 330 core


layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aTexCoord;
layout (location = 2) in vec3 aNormal;

// Daylight and Torchlight, each already divided by 15, and the corner occlusion 0..3.
layout (location = 3) in vec2 aLight;
layout (location = 4) in float aShade;

out vec2 TexCoord;
out vec3 Normal;
out vec3 FragPos;

// Attributes 3 and 4 are absent from the GUI quad and the selection box, and an absent
// attribute reads as zero rather than as "unset" — which is why the fragment shader takes
// the GUI path before it looks at either of these. Reading a zero here as a real light
// level would black out every screen-space quad in the game.
out vec2 Light;
out float Shade;

// Only the Minimap reads this. It is set to (-1,-1) for everything else, and the
// fragment shader uses that as its "not the minimap" test — a sentinel rather than a
// second draw mode, so the ordinary world and GUI paths are untouched by the minimap.
out vec2 MapLocal;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

uniform bool isEntity;
uniform vec2 uvOffset; // Tile X and Y (e.g. 0.0, 1.0 for wood)

uniform bool isMinimap;

void main() {
    FragPos = vec3(model * vec4(aPos, 1.0));
    Normal = mat3(transpose(inverse(model))) * aNormal;

    Light = aLight;
    Shade = aShade;

    if (isEntity) {
        // Map 0-1 range to the specific tile quadrant
        // (aTexCoord + vec2(0, 1)) / 4.0 if it's wood
        TexCoord = (aTexCoord + uvOffset) / 4.0;
    } else {
        TexCoord = aTexCoord;
    }

    if (isMinimap) {
        // The minimap quad already carries 0..1 UVs, so they are its map coordinates
        // directly — but they must not be divided by the atlas size.
        TexCoord = aTexCoord;
        MapLocal = aPos.xy + 0.5;
    } else {
        MapLocal = vec2(-1.0);
    }

    gl_Position = projection * view * vec4(FragPos, 1.0);
}
