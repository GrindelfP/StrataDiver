#version 330 core
out vec4 FragColor;

in vec2 TexCoord;
in vec3 Normal;

uniform sampler2D textureAtlas;
uniform vec3 sunDir;
uniform vec3 moonDir;

void main() {
    vec4 texColor = texture(textureAtlas, TexCoord);
    if(texColor.a < 0.1) discard;

    vec3 norm = normalize(Normal);

    // 1. Ambient: Very dim base light so nights aren't pitch black
    float ambient = 0.15;

    // 2. Sun Light (Yellowish)
    float sunDiff = max(dot(norm, normalize(sunDir)), 0.0);
    vec3 sunColor = sunDiff * vec3(1.0, 0.9, 0.8);

    // 3. Moon Light (Blueish/Dim)
    float moonDiff = max(dot(norm, normalize(moonDir)), 0.0);
    vec3 moonColor = moonDiff * vec3(0.2, 0.3, 0.5);

    vec3 finalLight = min(vec3(ambient) + sunColor + moonColor, vec3(1.0));
    FragColor = vec4(texColor.rgb * finalLight, texColor.a);
}
