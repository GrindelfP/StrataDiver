#version 330 core

// Temporal exponential moving average (EMA).
//
// output = lerp(history, current, currentWeight)
//
// This runs after the denoise pass and before present. The alpha controls how much
// of the new frame is blended in: higher weight = more responsive, less smoothing.
// At 0.92 the old-history coefficient is 0.08 per frame, so its half-life is
// ln(0.5)/ln(0.08) ~= 0.27 frames (not 12). This is a fast blend, not long memory.
//
// On reset (camera/world change), the history texture is cleared to 0, so no
// ghosting persists across scene changes.

in vec2 TexCoord;

uniform sampler2D uCurrent;  // denoised (or raw average if denoise off)
uniform sampler2D uHistory;  // previous frame's history
uniform float uAlpha;        // blend factor [0,1]

out vec4 FragColor;

void main() {
    vec3 curr = texture(uCurrent, TexCoord).rgb;
    vec3 hist = texture(uHistory, TexCoord).rgb;
    FragColor = vec4(mix(hist, curr, uAlpha), 1.0);
}
