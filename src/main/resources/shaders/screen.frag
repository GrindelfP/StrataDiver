#version 330 core

// The traced picture, copied to the screen untouched.

in vec2 TexCoord;
out vec4 FragColor;

uniform sampler2D image;

void main() {
    FragColor = vec4(texture(image, TexCoord).rgb, 1.0);
}