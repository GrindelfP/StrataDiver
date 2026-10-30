#version 330 core

// The average, cleaned.
//
// A bilateral filter: each pixel becomes the weighted mean of its neighbourhood, and the
// weight falls off with the offset *and* with how different the neighbour is. The
// difference is measured in the three things the trace pass already knows about the first
// surface the camera ray met — its normal, its depth and its albedo — because in a voxel
// world those are exactly what changes at an edge and exactly what a plain blur would
// smear across.
//
// Colour alone is not enough, and the reason is a grass block: two faces of it share a
// colour and differ only in depth, so a colour-guided filter softens the edge between the
// top and the side. The normal stops that (two axis-aligned faces differ by ninety
// degrees), the depth catches the cases the normal misses, and the albedo keeps the art's
// own detail sharp within a face.
//
// What is filtered is the *average*, not the live sample. The average has already done
// most of the work of removing noise; this removes the residue. A single-sample image is
// filtered too, gently, because the guides are strong enough to keep it from smearing
// across an edge — which is why the moving-camera grain is helped here and finished by
// the temporal accumulation rather than by this alone.

in vec2 TexCoord;

uniform sampler2D uImage;     // the accumulated radiance
uniform sampler2D uGbufferA;  // normal.xyz, depth
uniform sampler2D uGbufferB;  // albedo.rgb, 1
uniform vec2 uTexel;          // one texel, in UV

out vec4 FragColor;

const int RADIUS = 1;
const float NORMAL_POWER = 16.0;
const float DEPTH_SIGMA = 0.25;   // Cells: one-cell-separated layers contribute at most e^-8.
const float ALBEDO_SIGMA = 0.08;  // Linear albedo; preserve authored tile contrast.

void main() {
    // uImage and both guides are allocated at the same trace dimensions. uTexel is supplied
    // from those dimensions (not the display framebuffer), so every offset addresses the
    // matching current-sample guide when adaptive scale changes.
    vec4 centreA = texture(uGbufferA, TexCoord);
    vec3 centreN = centreA.xyz;
    float centreD = centreA.w;
    vec3 centreAlbedo = texture(uGbufferB, TexCoord).rgb;

    vec3 sum = texture(uImage, TexCoord).rgb;
    float weightSum = 1.0;

    for (int dy = -RADIUS; dy <= RADIUS; ++dy) {
        for (int dx = -RADIUS; dx <= RADIUS; ++dx) {
            if (dx == 0 && dy == 0) continue;

            vec2 uv = TexCoord + vec2(float(dx), float(dy)) * uTexel;
            vec4 g = texture(uGbufferA, uv);
            vec3 n = g.xyz;
            float d = g.w;
            vec3 albedo = texture(uGbufferB, uv).rgb;

            // Spatial: a plain gaussian, so the kernel is a blur when nothing else
            // stops it. One Cell of the neighbourhood is the right reach here — wide
            // enough to average out the residual, narrow enough not to invent detail.
            float spatial = exp(-0.5 * float(dx * dx + dy * dy));

            // Normal: the faces of this world are flat and axis-aligned, so two pixels on
            // one face share a normal exactly and two across an edge differ by ninety
            // degrees. The strongest of the three guides, and the reason colour alone
            // would be wrong.
            float normal = pow(max(dot(n, centreN), 0.0), NORMAL_POWER);

            // Depth: a surface edge the normal alone cannot see — two parallel faces, one
            // behind the other, have the same normal and are a Cell or more apart. A sky
            // pixel (depth 0) is left alone, because it has no surface to be compared.
            float depth = centreD > 0.0 && d > 0.0
                ? exp(-0.5 * pow((d - centreD) / DEPTH_SIGMA, 2.0))
                : (centreD == d ? 1.0 : 0.0);

            // Albedo: a texture edge within one face, where normal and depth are
            // identical and only the art changes.
            vec3 da = albedo - centreAlbedo;
            float albedoW = exp(-0.5 * dot(da, da) / (ALBEDO_SIGMA * ALBEDO_SIGMA));

            float w = spatial * normal * depth * albedoW;
            sum += w * texture(uImage, uv).rgb;
            weightSum += w;
        }
    }

    FragColor = vec4(sum / weightSum, 1.0);
}
