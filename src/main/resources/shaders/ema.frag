#version 330 core

// Temporal exponential moving average (EMA).
//
// history = lerp(history, current, alpha)
//
// This runs after the denoise pass and before present. The alpha controls how much
// of the new frame is blended in: higher alpha = more responsive, less smoothing.
// Default 0.92 gives ~12-frame half-life.
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