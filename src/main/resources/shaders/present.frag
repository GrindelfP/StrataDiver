#version 330 core

// Puts the finished average on the screen: film, then gamma.
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

in vec2 TexCoord;

uniform sampler2D uImage;
uniform float uExposure;

out vec4 FragColor;

void main() {
    vec3 v = texture(uImage, TexCoord).rgb;

    // NaN is not a colour and must not survive the chain: one NaN in the accumulator is a
    // NaN in the frame, and it is invisible in a screenshot and permanent in a game.
    v = mix(v, vec3(0.0), vec3(isnan(v.r), isnan(v.g), isnan(v.b)));
    v = max(v, vec3(0.0));

    vec3 tone = vec3(1.0) - exp(-v * uExposure);
    tone = pow(tone, vec3(1.0 / 2.2));

    FragColor = vec4(tone, 1.0);
}