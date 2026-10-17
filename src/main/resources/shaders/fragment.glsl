#version 330 core

out vec4 FragColor;

in vec2 TexCoord;
in vec3 Normal;
in vec2 Light;
in float Shade;

uniform sampler2D textureAtlas;
uniform vec3 sunDir;
uniform vec3 moonDir;

uniform bool isEntity; // A Dropped Item is lit by the Cell it lies in, not per-vertex
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

// --- Light ---
//
// A Dropped Item is drawn from the plain eight-float cube and has no per-vertex light, so
// its level arrives here instead. Set per Entity, from the Cell it is lying in.
uniform vec2 lightLevel;

// Daylight is very slightly cool and Torchlight is distinctly warm, which is the whole
// reason the two are kept apart rather than merged into one brightness: a tunnel lit by
// glowstone should read as a different place from the same tunnel at noon, and a single
// scalar cannot say that.
const vec3 DAY_COLOUR = vec3(0.97, 0.98, 1.0);
const vec3 TORCH_COLOUR = vec3(1.0, 0.74, 0.46);

// How dark a fully occluded corner gets. Occlusion is a contact shadow, not darkness, so
// this never reaches zero — a corner where three blocks meet is dimmer than an open wall,
// not black.
const float SHADE_FLOOR = 0.52;

// The floor of a Cell that has no light at all. Small rather than zero so that a sealed
// room is navigable by memory and by the outline of the selection box.
const float MIN_LIGHT = 0.07;

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

        vec3 directional = min(vec3(ambient) + sunColor + moonColor, vec3(1.0));

        // The two light channels, resolved to one colour and one level.
        vec2 light;
        float occlusion;
        if (isEntity) {
            light = lightLevel;
            occlusion = 1.0;   // An item is not in the grid, so nothing occludes it.
        } else {
            light = Light;
            occlusion = Shade / 3.0;
        }

        float level = max(light.x, light.y);
        vec3 lightColour = light.x >= light.y ? DAY_COLOUR : TORCH_COLOUR;

        // Outdoors this is very close to the old flat lighting, because open sky is 15 and
        // an unoccluded corner is 3 — so the change is invisible on a bare hillside and
        // enormous the moment there is a roof, an overhang or a wall.
        finalLight = directional
                   * lightColour
                   * mix(SHADE_FLOOR, 1.0, occlusion)
                   * (MIN_LIGHT + (1.0 - MIN_LIGHT) * level);
    }

    FragColor = vec4(texColor.rgb * finalLight, texColor.a);
}
