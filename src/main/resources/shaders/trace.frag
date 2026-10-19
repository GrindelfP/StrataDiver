#version 330 core

// One traced sample for every pixel, folded into the average.
//
// This is [PathTracer.trace] moved onto the GPU, deliberately not improved on: the same Sun
// disc, the same MIS weights, the same Russian roulette, the same cutout handling, the same
// aerial-perspective gate. The CPU tracer is kept as a test oracle for exactly this reason —
// two independent implementations of one algorithm have to agree, and if they do not, one of
// them is wrong and the comparison says which.
//
// Everything the game knows about a Block Type arrives as a uniform rather than as a table
// the shader owns, so adding a Block Type stays a one-line change on the Kotlin side and the
// shader never has to be edited to agree about what a leaf is.

uniform vec3 uEye;
uniform vec3 uFront;
uniform vec3 uUp;
uniform float uTanHalf;
uniform float uAspect;
uniform vec2 uResolution;

// The world, as two channels per Cell: the Block Type's id, and the Cell's packed light.
uniform sampler3D uVolume;
uniform ivec3 uOrigin;   // world Cell of volume texel (0,0,0)
uniform ivec3 uSize;     // volume size in Cells

uniform sampler2D uAtlas;

uniform int uTypeCount;
uniform ivec4 uOpaque[8];      // x = opaque
uniform ivec4 uEmission[8];    // x = emission
uniform ivec4 uAttenuation[8]; // x = attenuation, for light passing through
uniform vec4 uTileUV[48];      // 8 types x 6 faces, xy = atlas offset

uniform vec3 uSunDir;
uniform vec3 uMoonDir;
uniform float uDaylight;

uniform int uSampleIndex;
uniform int uSampleCount;

uniform vec3 uSelection;   // the Cell being aimed at
uniform float uSelectionOn;

/**
 * What to show instead of the picture.
 *
 * 0 the traced radiance, which is what the game draws. 1 the albedo of the first thing the
 * camera ray hit, unlit — which answers "is the art being read correctly" on its own.
 * 2 the Daylight in the Cell that was hit — which answers "is the light reaching the GPU",
 * and if it is not, every aerial-perspective decision downstream is being made on zeros
 * without a word of complaint.
 */
uniform int uDebug;

const float MAX_DISTANCE = 128.0;
const int MAX_DEPTH = 5;
const int RR_START = 3;
const float SUN_ANGLE = 0.035;
const float SUN_IRRADIANCE = 3.2;
const float MOON_IRRADIANCE = 0.035;
const float EMISSION_RADIANCE = 6.0;
const float FOG_DENSITY = 0.0055;
const float FIREFLY_CLAMP = 12.0;
const float SURFACE_EPS = 1e-3;
const float LINE_HALF_WIDTH = 0.008;
const float INV_PI = 0.31830988618;
const float SKY_MIS_WEIGHT = 0.5;
const float EMISSION_SCALE = EMISSION_RADIANCE / 15.0;
const float TAU = 6.28318530718;

const vec3 ZENITH = vec3(0.22, 0.38, 0.85);
const vec3 HORIZON = vec3(0.62, 0.72, 0.92);
const vec3 GROUND = vec3(0.20, 0.18, 0.15);

// Matches Atlas: 4x4 tiles of 64px, each holding 16x16 art enlarged fourfold with a guard
// ring of two pixels. Written out rather than derived, because an index that is one texel
// out of bounds is a black speckle in the canopy and nothing else.
const int TILE_PX = 64;
const int ART_PX = 16;
const int PAD = 2;
const int UPSCALE = 4;

const int TOP = 0, BOTTOM = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;

struct Hit {
    bool  hit;
    float t;
    ivec3 cell;
    int   face;
    int   type;
    vec2  uv;
    vec3  n;
};

/**
 * What the last walk found, in a global rather than an `out` parameter.
 *
 * GLSL will happily pass a seven-field struct by reference, and every call site pays for
 * it: the compiler keeps it somewhere that is not registers, and this walk runs about
 * sixteen times a pixel. A global is the same data with none of the address arithmetic,
 * and it is the ordinary way a shader says "this is a result, not an input".
 */
Hit gHit;

out vec4 FragColor;

// --- Randomness ---

// A stateless integer hash rather than a carried generator: one pixel's ray must not be
// able to influence another's, and a hash makes that impossible to get wrong rather than
// merely unlikely. The CPU tracer seeds per pixel for the same reason.
uint hashU(uint x) {
    x ^= x >> 16; x *= 0x7feb352du;
    x ^= x >> 15; x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

uint seedOf(uvec3 p) { return hashU(p.x * 73856093u ^ hashU(p.y * 19349663u ^ p.z * 83492791u)); }

float rand(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint word = ((s >> ((s >> 28) + 4u)) ^ s) * 277803737u;
    return float((word >> 22) ^ word) * 2.3283064365386963e-10;
}

// Halton for the sub-pixel jitter, rotated per pixel by a random offset. On its own Halton
// lays the same pattern into every pixel and the image grows a visible weave; the rotation
// turns that into noise, which averages out.
float halton(int index, int base) {
    float f = 1.0, r = 0.0;
    int i = index;
    while (i > 0) {
        f /= float(base);
        r += f * float(i % base);
        i /= base;
    }
    return r;
}

// --- The world as data ---

vec2 volumeFetch(ivec3 c) {
    ivec3 v = c - uOrigin;
    if (any(lessThan(v, ivec3(0))) || any(greaterThanEqual(v, uSize))) return vec2(0.0);
    return texelFetch(uVolume, v, 0).rg;
}

int typeAt(ivec3 c) { return int(volumeFetch(c).r * 255.0 + 0.5); }

/** Daylight, 0..15, or 0 where the world has not been written. */
int daylightAt(ivec3 c) { return int(floor(volumeFetch(c).g * 255.0 + 0.5)) >> 4; }

bool opaqueOf(int type) { return uOpaque[type].x == 1; }
int emissionOf(int type) { return uEmission[type].x; }
vec2 tileUV(int type, int face) { return uTileUV[type * 6 + face].xy; }

vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }

vec2 artTexel(int type, int face, vec2 uv) {
    vec2 offset = tileUV(type, face);
    ivec2 art = ivec2(clamp(uv * float(ART_PX), vec2(0.0), vec2(float(ART_PX - 1))));
    return offset * float(TILE_PX) + float(PAD) + vec2(art) * float(UPSCALE);
}

/**
 * The colour of one point on one Tile, in linear light.
 *
 * A point sample and never a filtered one: a ray lands somewhere on the 16x16 art and every
 * ray lands somewhere different, which averages to the Tile's own mean colour at a distance.
 * A mipmapped lookup would be cheaper and wrong in exactly the way this renderer is trying
 * not to be wrong.
 */
vec3 sampleArt(int type, int face, vec2 uv) {
    return toLinear(texelFetch(uAtlas, ivec2(artTexel(type, face, uv)), 0).rgb);
}

/**
 * Whether the art at this point is a hole rather than a surface.
 *
 * The cutout, honoured by the ray and not settled once: a hole in a leaf is a hole in its
 * shadow, re-decided per ray, so a canopy edge dithers into its own shadow rather than
 * being decided once and wrongly.
 */
bool artIsHole(int type, int face, vec2 uv) {
    return texelFetch(uAtlas, ivec2(artTexel(type, face, uv)), 0).a < 0.1;
}

vec2 faceUV(int face, vec3 p) {
    if (face == TOP || face == BOTTOM) return p.xz;
    if (face == NORTH || face == SOUTH) return p.xy;
    return p.zy;
}

vec3 faceNormal(int face) {
    if (face == TOP) return vec3(0, 1, 0);
    if (face == BOTTOM) return vec3(0, -1, 0);
    if (face == NORTH) return vec3(0, 0, -1);
    if (face == SOUTH) return vec3(0, 0, 1);
    if (face == WEST) return vec3(-1, 0, 0);
    return vec3(1, 0, 0);
}

// --- Walking the Cells ---

/**
 * Amanatides and Woo, stepping from Cell to Cell.
 *
 * Both failures this shape exists to avoid are in the record: a fixed step fine enough not
 * to skip a Cell is a thousand steps a ray, and a step that can skip one puts holes in
 * geometry nothing else reports.
 *
 * The walk stops at the volume's edge, which is the single biggest thing keeping this fast.
 * Without it a ray toward the sky steps all 128 Cells of `MAX_DISTANCE` through empty space
 * finding nothing, and there are three of those per surface and three surfaces per pixel.
 * Outside the volume there is nothing to hit by construction, so the walk can end there
 * without changing an answer — the Cells beyond it read as AIR anyway, which is why the
 * aerial perspective is what turns that edge into haze rather than a cliff.
 */
bool intersectWorld(vec3 o, vec3 d, float maxT) {
    vec3 lo = vec3(uOrigin);
    vec3 hi = vec3(uOrigin + uSize);

    // Where the ray leaves the volume, by the slab method. A ray already inside gets a
    // positive exit distance; a ray starting outside that never comes back gets an early
    // one, which ends the walk immediately rather than marching to the origin.
    //
    // The near-zero substitution has to preserve the *sign* of the direction. Turning a
    // parallel component into +1e-20 would make a ray going straight up think its x slab is
    // 1e20 Cells wide, and the box test below would then reject rays that are plainly inside
    // — which is a world that disappears in a way that looks like a rendering bug.
    vec3 safeDir = vec3(
        abs(d.x) < 1e-20 ? (d.x < 0.0 ? -1e-20 : 1e-20) : d.x,
        abs(d.y) < 1e-20 ? (d.y < 0.0 ? -1e-20 : 1e-20) : d.y,
        abs(d.z) < 1e-20 ? (d.z < 0.0 ? -1e-20 : 1e-20) : d.z
    );
    vec3 inv = 1.0 / safeDir;
    vec3 t0 = (lo - o) * inv;
    vec3 t1 = (hi - o) * inv;
    vec3 near = min(t0, t1);
    vec3 far = max(t0, t1);
    float enter = max(max(near.x, near.y), near.z);
    float exit = min(min(far.x, far.y), far.z);
    if (exit < 0.0 || enter > exit) return false;

    float limit = min(maxT, exit);

    ivec3 c = ivec3(floor(o));
    vec3 tDelta = vec3(1e30);
    vec3 tMax = vec3(1e30);
    vec3 p = o - vec3(c);

    if (d.x != 0.0) {
        tDelta.x = abs(1.0 / d.x);
        tMax.x = (d.x > 0.0 ? (float(c.x + 1) - o.x) : (o.x - float(c.x))) * tDelta.x;
    }
    if (d.y != 0.0) {
        tDelta.y = abs(1.0 / d.y);
        tMax.y = (d.y > 0.0 ? (float(c.y + 1) - o.y) : (o.y - float(c.y))) * tDelta.y;
    }
    if (d.z != 0.0) {
        tDelta.z = abs(1.0 / d.z);
        tMax.z = (d.z > 0.0 ? (float(c.z + 1) - o.z) : (o.z - float(c.z))) * tDelta.z;
    }
    ivec3 istep = ivec3(
        d.x > 0.0 ? 1 : -1,
        d.y > 0.0 ? 1 : -1,
        d.z > 0.0 ? 1 : -1
    );

    float t = 0.0;
    int face = -1;

    for (int guard = 0; guard < 400; ++guard) {
        if (t > limit) break;
        int type = typeAt(c);

        if (type != 0) {
            int entry = face < 0 ? TOP : face;
            vec3 q = clamp(o + d * t - vec3(c), vec3(0.0), vec3(0.999999));
            if (opaqueOf(type) || !artIsHole(type, entry, faceUV(entry, q))) {
                gHit.hit = true;
                gHit.t = t;
                gHit.cell = c;
                gHit.type = type;
                gHit.face = entry;
                gHit.uv = faceUV(entry, q);
                gHit.n = faceNormal(entry);
                return true;
            }
        }

        if (tMax.x <= tMax.y && tMax.x <= tMax.z) {
            t = tMax.x; tMax.x += tDelta.x; c.x += istep.x;
            face = istep.x > 0 ? WEST : EAST;
        } else if (tMax.y <= tMax.z) {
            t = tMax.y; tMax.y += tDelta.y; c.y += istep.y;
            face = istep.y > 0 ? BOTTOM : TOP;
        } else {
            t = tMax.z; tMax.z += tDelta.z; c.z += istep.z;
            face = istep.z > 0 ? NORTH : SOUTH;
        }
    }
    return false;
}

// --- Light ---

vec3 skyRadiance(vec3 d, bool withSun) {
    float blend = pow(max(0.0, d.y), 0.45);
    vec3 c = HORIZON + (ZENITH - HORIZON) * blend;

    if (d.y < 0.0) {
        float down = min(-d.y, 1.0);
        c += (GROUND * uDaylight * down - c) * down;
    }

    // Around the Sun: a tight glow and a broad one, because a real sky is bright well away
    // from the disc, and only the glow is what makes the far side of a clearing warmer than
    // the near side.
    float cosSun = dot(d, uSunDir);
    if (cosSun > 0.0) {
        float tight = pow(cosSun, 64.0);
        float broad = pow(cosSun, 6.0);
        c += vec3(tight * 0.90 + broad * 0.10,
                  tight * 0.75 + broad * 0.08,
                  tight * 0.50 + broad * 0.05) * uDaylight;
    }

    // The Sun's own disc, and only on the first bounce: every bounce below it has the Sun
    // already accounted for by its own direct lighting, and counting it again would be
    // counting it twice.
    if (withSun && cosSun > cos(SUN_ANGLE)) c += vec3(SUN_IRRADIANCE);

    return c;
}

void basis(vec3 n, out vec3 t, out vec3 b) {
    t = abs(n.z) < 0.9 ? normalize(cross(vec3(0, 0, 1), n)) : normalize(cross(vec3(1, 0, 0), n));
    b = cross(n, t);
}

/** A direction drawn cosine-weighted about n, so the sampling and the BSDF agree. */
vec3 cosineHemisphere(inout uint s, vec3 n) {
    float u = rand(s);
    float phi = TAU * rand(s);
    float r = sqrt(u);
    vec3 t, b;
    basis(n, t, b);
    return normalize(t * (r * cos(phi)) + b * (r * sin(phi)) + n * sqrt(max(0.0, 1.0 - u)));
}

/** A direction drawn from inside a disc of angular radius [angle] about [axis]. */
vec3 sampleCone(inout uint s, vec3 axis, float angle) {
    float u = rand(s);
    float phi = TAU * rand(s);
    float cosTheta = mix(cos(angle), 1.0, u);
    float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
    vec3 t, b;
    basis(axis, t, b);
    return normalize(t * (sinTheta * cos(phi)) + b * (sinTheta * sin(phi)) + axis * cosTheta);
}

bool occluded(vec3 o, vec3 d, float maxT) {
    return intersectWorld(o, d, maxT);
}

/**
 * Direct lighting: the Sun and the Moon as area lights, then one sample of the sky.
 *
 * Treating them as area lights is the whole reason a shadow edge is a penumbra that
 * sharpens as the average accumulates. The real Sun's half-degree disc would need thousands
 * of samples to stop looking like static, so [SUN_ANGLE] is four times it, deliberately.
 */
vec3 directLight(inout uint s, vec3 p, vec3 n, vec3 albedo) {
    vec3 result = vec3(0.0);

    // The Sun. Its radiance carries the time of day, which is why a low Sun is not merely
    // a Sun at a grazing angle but a dimmer Sun, and its colour is very slightly warm.
    float cosSun = dot(n, uSunDir);
    if (cosSun > 0.0) {
        vec3 cone = sampleCone(s, uSunDir, SUN_ANGLE);
        if (!occluded(p, cone, MAX_DISTANCE)) {
            float omega = TAU * (1.0 - cos(SUN_ANGLE)) * cosSun;
            result += albedo * INV_PI * SUN_IRRADIANCE * uDaylight * vec3(1.0, 0.97, 0.90) * omega;
        }
    }

    // The Moon, opposite the Sun, and only once it is itself above the horizon. The clamp
    // is on purpose: without it a Sun below the horizon gives negative moonlight, and
    // negative light is the sort of thing that surfaces three functions away as a black sky.
    float moonUp = clamp(-uSunDir.y, 0.0, 1.0);
    if (moonUp > 0.0) {
        float cosMoon = dot(n, -uSunDir);
        if (cosMoon > 0.0) {
            vec3 cone = sampleCone(s, -uSunDir, SUN_ANGLE);
            if (!occluded(p, cone, MAX_DISTANCE)) {
                float omega = TAU * (1.0 - cos(SUN_ANGLE)) * cosMoon;
                result += albedo * INV_PI * MOON_IRRADIANCE * moonUp * vec3(1.0, 1.05, 1.30) * omega;
            }
        }
    }

    // The sky: one cosine-weighted sample, and the cosine and the 1/pi of the pdf cancel
    // exactly. The other half of this integral is the bounce that escapes, and MIS is what
    // stops the two halves counting the sky twice.
    vec3 w = cosineHemisphere(s, n);
    if (!occluded(p, w, MAX_DISTANCE)) {
        result += albedo * SKY_MIS_WEIGHT * skyRadiance(w, false);
    }

    // Fireflies are born here and nowhere else, so this is where the clamp belongs.
    return min(result, vec3(FIREFLY_CLAMP));
}

/**
 * The wireframe around the Cell being aimed at.
 *
 * The last thing in the pixel, and deliberately not a surface: it stops nothing, lights
 * nothing, and would be nonsense if it scattered. Like everything else it is a screen-space
 * coverage rather than a surface the ray hits, because there is no depth buffer behind the
 * picture for anything to sort against.
 */
float selectionCoverage(vec3 origin, vec3 dir, float tLimit) {
    if (uSelectionOn < 0.5) return 0.0;

    vec3 cell = uSelection;
    // Entry into the Cell's box.
    vec3 inv = 1.0 / dir;
    vec3 t0 = (cell - origin) * inv;
    vec3 t1 = (cell + 1.0 - origin) * inv;
    vec3 lo = min(t0, t1), hi = max(t0, t1);
    float tEnter = max(max(lo.x, lo.y), lo.z);
    float tExit = min(min(hi.x, hi.y), hi.z);
    if (tEnter > tExit || tExit < 0.0) return 0.0;
    if (tEnter > tLimit) return 0.0;

    vec3 p = origin + dir * tEnter - cell;
    // Which face was entered, so the other two axes span it.
    vec3 fromFace = vec3(p.x, p.y, p.z);
    if (tEnter == lo.x) fromFace.y = 0.0, fromFace.z = 0.0;
    else if (tEnter == lo.y) fromFace.x = 0.0, fromFace.z = 0.0;
    else fromFace.x = 0.0, fromFace.y = 0.0;

    float toEdge = 1.0;
    for (int i = 0; i < 3; ++i) {
        if (fromFace[i] > 0.0) toEdge = min(toEdge, min(fromFace[i], 1.0 - fromFace[i]));
    }

    float pixel = 2.0 * uTanHalf * max(tEnter, 1e-3) / uResolution.y;
    return clamp(0.5 + (LINE_HALF_WIDTH - toEdge) / max(pixel, 1e-6), 0.0, 1.0);
}

void main() {
    ivec2 frag = ivec2(gl_FragCoord.xy);
    uint seed = seedOf(uvec3(frag, uint(uSampleIndex)));

    float jx = float(frag.x) + fract(halton(uSampleIndex + 1, 2) + rand(seed));
    float jy = float(frag.y) + fract(halton(uSampleIndex + 1, 3) + rand(seed));

    float sx = (2.0 * jx / uResolution.x - 1.0) * uTanHalf * uAspect;
    float sy = (1.0 - 2.0 * jy / uResolution.y) * uTanHalf;

    vec3 f = normalize(uFront);
    vec3 u = normalize(uUp - f * dot(uUp, f));
    vec3 v = cross(f, u);
    vec3 d = normalize(f + u * sx + v * sy);
    vec3 startDir = d;

    vec3 ray = uEye;
    vec3 throughput = vec3(1.0);
    vec3 radiance = vec3(0.0);

    float firstDistance = -1.0;
    ivec3 firstCell = ivec3(0);

    int depth = 0;
    for (; depth <= MAX_DEPTH; ++depth) {
        bool hit = intersectWorld(ray, d, MAX_DISTANCE);
        Hit h = gHit;

        if (!hit) {
            float weight = depth == 0 ? 1.0 : SKY_MIS_WEIGHT;
            radiance += throughput * skyRadiance(d, depth == 0) * weight;
            break;
        }

        if (depth == 0) {
            firstDistance = h.t;
            firstCell = h.cell;
        }

        // Step off the surface along its own normal before anything samples light from
        // here. A hit lands on a Cell boundary and rounding decides which side of it the
        // coordinates fall on, so a shadow ray started at the raw hit point has a good
        // chance of starting *inside* the solid it is standing on — and then every surface
        // in the world reports itself occluded. That looks like a world lit only by its own
        // reflections: plausible, and not one error but the absence of light.
        vec3 p = ray + d * h.t + h.n * SURFACE_EPS;

        vec3 albedo = sampleArt(h.type, h.face, h.uv);

        float emit = float(emissionOf(h.type)) * EMISSION_SCALE;
        if (emit > 0.0) radiance += throughput * albedo * emit;

        radiance += throughput * directLight(seed, p, h.n, albedo);

        d = cosineHemisphere(seed, h.n);
        throughput *= albedo;
        ray = p;

        if (depth + 1 >= RR_START) {
            float keep = max(albedo.r, max(albedo.g, albedo.b));
            if (keep < 1.0 && rand(seed) > keep) break;
        }
    }

    // Aerial perspective, applied once, over the distance the eye can see. Gated on whether
    // the sky reaches the Cell that was hit, because the air scatters the sky's light and
    // there is none to scatter where the sky does not reach.
    if (firstDistance > 0.0 && daylightAt(firstCell) > 0) {
        float transmittance = exp(-FOG_DENSITY * firstDistance);
        if (transmittance < 1.0) {
            vec3 haze = skyRadiance(startDir, false);
            radiance = radiance * transmittance + haze * (1.0 - transmittance);
        }
    }

    float cover = selectionCoverage(uEye, startDir, firstDistance > 0.0 ? firstDistance : MAX_DISTANCE);
    radiance += (vec3(1.0) - radiance) * cover;

    if (uDebug == 1) {
        // The first thing the camera ray met, unlit. Not a bounce: this is about whether
        // the volume and the art are being read at all.
        if (intersectWorld(uEye, startDir, MAX_DISTANCE)) {
            Hit first = gHit;
            radiance = sampleArt(first.type, first.face, first.uv);
        } else {
            radiance = vec3(0.0, 0.0, 0.2);
        }
    } else if (uDebug == 2) {
        radiance = firstDistance > 0.0
            ? vec3(float(daylightAt(firstCell)) / 15.0)
            : vec3(0.0, 0.0, 0.2);
    }

    FragColor = vec4(radiance, 1.0);
}