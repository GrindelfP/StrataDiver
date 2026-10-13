#version 330 core

out vec4 FragColor;

in vec2 TexCoord;
in vec3 Normal;

uniform sampler2D textureAtlas;
uniform vec3 sunDir;
uniform vec3 moonDir;

uniform bool isGui;   // GUI is drawn unlit, so it never flickers with the time of day
uniform vec3 tint;    // Per-face brightness for composited Icons

// --- Minimap ---
// The map's world arrives as a texture rather than being computed here. A fragment shader
// has no access to the world's Cells at all; the branch this replaced synthesised
// plausible terrain out of sine waves because computing it for real was not possible.
uniform sampler2D minimapMap;

// Where the player sits in the map, in 0..1 map space.
uniform vec2 minimapOrigin;

// The player's facing as a unit vector in map space, where +y is map-down — which is
// already the texture's own orientation, so no flip is needed here.
uniform vec2 minimapFacing;

// Screen-space position of this fragment within the map quad, 0..1. Used for the round
// clip and the rim, which are circle problems and not texture problems.
in vec2 MapLocal;

void main() {
    // --- The Minimap, and only the Minimap ---
    if (MapLocal.x >= 0.0) {
        // Centre of the quad in 0..1 space, then distance from it in quad units.
        vec2 p = MapLocal - 0.5;
        float dist = length(p);

        // The disc. A quad is square; a minimap is round, and the corners are the only
        // part of the geometry that has to be thrown away.
        if (dist > 0.5) discard;

        vec3 mapRGB = texture(minimapMap, TexCoord).rgb;

        // The rim: the outermost sliver, opaque and dark, so the map reads as an object
        // on the screen rather than bleeding into whatever is behind it.
        if (dist > 0.47) {
            mapRGB = vec3(0.06, 0.06, 0.08);
        }

        // The player: a dot at the exact position, plus a tick along the facing. Without
        // the tick a top-down map cannot say which way you are looking, which is most of
        // what you consult a map for while moving.
        vec2 toPlayer = p - (minimapOrigin - 0.5);
        if (length(toPlayer) < 0.022) {
            mapRGB = vec3(1.0);
        } else if (abs(dot(toPlayer, vec2(-minimapFacing.y, minimapFacing.x))) < 0.012 &&
                   dot(toPlayer, minimapFacing) > 0.0 &&
                   dot(toPlayer, minimapFacing) < 0.10) {
            mapRGB = vec3(1.0);
        }

        FragColor = vec4(mapRGB, 1.0);
        return;
    }

    // --- Everything else: the world and the ordinary GUI ---
    vec4 texColor = texture(textureAtlas, TexCoord);
    if (texColor.a < 0.1) discard;

    vec3 finalLight;

    if (isGui) {
        // GUI is unlit and deterministic; Icons carry their own face shading through
        // `tint` so the three faces of a cube stay distinguishable.
        finalLight = tint;
    } else {
        vec3 norm = normalize(Normal);

        // 1. Ambient: Very dim base light so nights aren't pitch black
        float ambient = 0.15;

        // 2. Sun Light (Yellowish)
        float sunDiff = max(dot(norm, normalize(sunDir)), 0.0);
        vec3 sunColor = sunDiff * vec3(1.0, 0.9, 0.8);

        // 3. Moon Light (Blueish/Dim)
        float moonDiff = max(dot(norm, normalize(moonDir)), 0.0);
        vec3 moonColor = moonDiff * vec3(0.2, 0.3, 0.5);

        finalLight = min(vec3(ambient) + sunColor + moonColor, vec3(1.0));
    }

    FragColor = vec4(texColor.rgb * finalLight, texColor.a);
}
