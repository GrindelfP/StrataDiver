#version 330 core
out vec4 FragColor;
in vec2 TexCoord;
uniform sampler2D textureAtlas;

void main() {
    vec4 texColor = texture(textureAtlas, TexCoord);
    if(texColor.a < 0.1) discard; // Transparency for crosshair/leaves
    FragColor = texColor;
}