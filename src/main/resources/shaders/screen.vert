#version 330 core

// One quad covering the window. The picture is already finished — averaged, film-toned and
// gamma-corrected on the CPU, because the same bytes have to be inspectable by
// tools/Check.kt without a window. All this shader does is put them on the screen.

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aTexCoord;

out vec2 TexCoord;

uniform mat4 model;
uniform mat4 projection;

void main() {
    TexCoord = aTexCoord;
    gl_Position = projection * model * vec4(aPos, 1.0);
}