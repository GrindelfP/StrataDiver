#version 330 core

// The GUI pass's fragment shader. Two paths and no third: the Minimap, which is a disc cut
// out of a square, and everything else, which is a flat Tile of the Atlas.
//
// There is no lighting here and there never needed to be. The GUI is drawn unlit so it
// never flickers with the time of day, and the only shading it has ever wanted is the
// per-face brightness an Icon composites its three faces with — which is what `tint` is,
// and it is not a light.

out vec4 FragColor;

in vec2 TexCoord;
in vec2 MapLocal;

uniform sampler2D textureAtlas;
uniform sampler2D minimapMap;

// Where the player sits in the map, in 0..1 map space.
uniform vec2 minimapOrigin;

// The player's facing as a unit vector in map space, where +y is map-down — which is
// already the texture's own orientation, so no flip is needed here.
uniform vec2 minimapFacing;

// Per-face brightness for composited Icons, so the three faces of a cube stay readable.
uniform vec3 tint;

void main() {
    // --- The Minimap, and only the Minimap ---
    if (MapLocal.x >= 0.0) {
        // Centre of the quad in 0..1 space, then distance from it in quad units.
        vec2 p = MapLocal - 0.5;
        float dist = length(p);

        // The disc. A quad is square; a Minimap is round, and the corners are the only
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

    // --- Everything else: an ordinary flat Tile of the Atlas ---
    vec4 texColor = texture(textureAtlas, TexCoord);
    if (texColor.a < 0.1) discard;

    FragColor = vec4(texColor.rgb * tint, texColor.a);
}