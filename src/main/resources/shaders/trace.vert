#version 330 core

// A fullscreen triangle-ish quad: the vertex shader for a screen-space pass that traces one
// ray per pixel. There is nothing here but the pass-through of the fragment coordinate.

layout (location = 0) in vec3 aPos;

void main() {
    gl_Position = vec4(aPos.xy, 0.0, 1.0);
}