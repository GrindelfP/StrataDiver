#version 330 core

// Puts the finished average on the screen: sharpen, film, then gamma.
//
// This is where the tone map went when it moved off the CPU. It used to be `rgba()`, which
// cost 173 nanoseconds a pixel on one thread — 40 ms a frame at the resolution the game
// was actually running, and outside the window the resolution controller was measuring, so
// nothing had ever seen it. The picture arrives here as floats and leaves as bytes.
//
// The chain is unchanged from that function, because it was never the arithmetic that was
// slow: it was doing it six times per pixel on the main thread. 1-exp(-v*EXPOSURE) raised to
// 1/2.2 is what turns radiance into a colour, and without the exponential the Sun's own
// radiance clips to white across a huge area while without the gamma the whole thing reads
// as a dark room with the lights on.
//
// The sharpen is new, and it is here because the image is 1:1 with the window: there is no
// upscale to hide behind, so any softness left by the denoiser and by the path tracer's own
// point sampling shows. An unsharp mask subtracts the neighbourhood mean and adds the
// difference back, which puts the edge where it already was rather than inventing one.

in vec2 TexCoord;

uniform sampler2D uImage;
uniform float uExposure;
uniform vec2 uTexel;      // one texel, in UV
uniform float uSharpen;   // how much of the difference to add back

out vec4 FragColor;

const int SHARP_RADIUS = 1;

vec3 sanitize(vec3 c) {
    c = mix(c, vec3(0.0), vec3(isnan(c.r), isnan(c.g), isnan(c.b)));
    return max(c, vec3(0.0));
}

void main() {
    vec3 v = sanitize(texture(uImage, TexCoord).rgb);

    // The neighbourhood mean, in linear light, at the image's own resolution.
    vec3 mean = vec3(0.0);
    for (int dy = -SHARP_RADIUS; dy <= SHARP_RADIUS; ++dy) {
        for (int dx = -SHARP_RADIUS; dx <= SHARP_RADIUS; ++dx) {
            mean += sanitize(texture(uImage, TexCoord + vec2(float(dx), float(dy)) * uTexel).rgb);
        }
    }
    mean /= float((2 * SHARP_RADIUS + 1) * (2 * SHARP_RADIUS + 1));

    v = max(v + uSharpen * (v - mean), vec3(0.0));

    vec3 tone = vec3(1.0) - exp(-v * uExposure);
    bvec3 low = lessThanEqual(tone, vec3(0.0031308));
    vec3 encodedLow = tone * 12.92;
    vec3 encodedHigh = 1.055 * pow(tone, vec3(1.0 / 2.4)) - 0.055;
    tone = mix(encodedHigh, encodedLow, low);

    FragColor = vec4(tone, 1.0);
}
