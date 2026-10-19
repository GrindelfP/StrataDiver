#version 330 core

// A fullscreen pass with no matrices.
//
// Its own vertex shader rather than the GUI's, and the difference matters more than it
// looks. A vertex shader that transforms by `model` and `projection` uniforms draws nothing
// at all if those uniforms are never set — and an unset `mat4` defaults to all zeros, so
// every vertex collapses to a degenerate point and the pass renders into the void without a
// single error. A pass whose job is "cover the target" should say so in its own words
// instead of depending on two uniforms it has no use for.

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aTexCoord;

out vec2 TexCoord;

void main() {
    TexCoord = aTexCoord;
    gl_Position = vec4(aPos.xy, 0.0, 1.0);
}