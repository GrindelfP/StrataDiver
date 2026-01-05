#version 330 core
out vec4 FragColor;

in vec2 TexCoord;
in vec3 Normal;

uniform sampler2D textureAtlas;
uniform vec3 sunDir;
uniform vec3 moonDir;
uniform bool isMap;

uniform vec2 playerPos; // X and Z of player
uniform float mapZoom;  // How much area to show

void main() {
    if (isMap) {
        vec2 uv = TexCoord * 2.0 - 1.0;
        float dist = length(uv);
        if (dist > 1.0) discard;

        // Calculate world coordinates for this pixel on the map
        // TexCoord 0..1 maps to the area around the player
        vec2 worldCoord = playerPos + (TexCoord - 0.5) * mapZoom;

        // Simple procedural map color based on "fake" noise
        // (Since we can't run the complex Perlin class here easily,
        // we use a simple sine-based approximation or pass a heightmap)
        float noise = sin(worldCoord.x * 0.1) * cos(worldCoord.y * 0.1);

        vec3 mapRGB = vec3(0.2, 0.5, 0.2); // Default Grass Green
        if (noise > 0.5) mapRGB = vec3(0.5, 0.5, 0.5); // "Mountains"

        // Border and Player Dot
        if (dist > 0.96) mapRGB = vec3(0.0);
        if (dist < 0.04) mapRGB = vec3(1.0, 0.0, 0.0);

        FragColor = vec4(mapRGB, 1.0);
    } else {
        vec4 texColor = texture(textureAtlas, TexCoord);
        if (texColor.a < 0.1) discard;

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
}
