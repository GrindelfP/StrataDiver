#version 330 core


layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aTexCoord;
layout (location = 2) in vec3 aNormal;

out vec2 TexCoord;
out vec3 Normal;
out vec3 FragPos;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

uniform bool isEntity;
uniform vec2 uvOffset; // Tile X and Y (e.g., 0.0, 1.0 for wood)

void main() {
    FragPos = vec3(model * vec4(aPos, 1.0));
    Normal = mat3(transpose(inverse(model))) * aNormal;

    if (isEntity) {
        // Map 0-1 range to the specific tile quadrant
        // (aTexCoord + vec2(0, 1)) / 4.0 if it's wood
        TexCoord = (aTexCoord + uvOffset) / 4.0;
    } else {
        TexCoord = aTexCoord;
    }

    gl_Position = projection * view * vec4(FragPos, 1.0);
}



//layout (location = 0) in vec3 aPos;
//layout (location = 1) in vec2 aTexCoord;
//layout (location = 2) in vec3 aNormal;
//
//out vec2 TexCoord;
//out vec3 Normal;
//
//uniform mat4 model;
//uniform mat4 view;
//uniform mat4 projection;
//
//void main() {
//    gl_Position = projection * view * model * vec4(aPos, 1.0);
//    TexCoord = aTexCoord;
//    Normal = aNormal;
//}