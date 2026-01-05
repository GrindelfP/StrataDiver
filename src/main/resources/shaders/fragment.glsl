#version 330 core
out vec4 FragColor;

in vec2 TexCoord;
in vec3 Normal;

uniform sampler2D textureAtlas;
uniform vec3 sunDir; // Direction of the sun

void main() {
    vec4 texColor = texture(textureAtlas, TexCoord);
    if(texColor.a < 0.1) discard;

    // 1. Ambient light (so shadows aren't pitch black)
    float ambient = 0.3;

    // 2. Diffuse light (The Sun)
    // We use dot product to see how aligned the face is with the sun
    float diff = max(dot(Normal, normalize(sunDir)), 0.0);

    // Combine them (limit to 1.0 max brightness)
    float light = min(ambient + diff, 1.0);

    FragColor = vec4(texColor.rgb * light, texColor.a);
}
