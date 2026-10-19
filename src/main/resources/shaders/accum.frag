#version 330 core

// The accumulator. One pass: add the sample just traced to what is already there, and write
// the mean back.
//
// Two textures in, because there are two things to add: the average so far, and the sample
// this frame produced. Reading only the previous average and calling it "the accumulation"
// is a plausible-looking way to write this and produces a black screen forever, because the
// first frame divides nothing by nothing and every later frame averages the same stale sum.
//
// Kept separate from the tracing pass on purpose. The traced picture is the renderer's
// output and nothing downstream should be able to quietly change what it means — so the
// average lives in one file doing exactly one thing, and the film lives in a third.

in vec2 TexCoord;

uniform sampler2D uPrevious;  // the mean of everything traced so far
uniform sampler2D uSample;    // this frame's sample
uniform float uCount;         // how many are in uPrevious

out vec4 FragColor;

void main() {
    vec3 previous = texture(uPrevious, TexCoord).rgb;
    vec3 sample = texture(uSample, TexCoord).rgb;
    // Guarded rather than trusted: a NaN that reaches the accumulator is permanent, and
    // there is no later frame in which it washes out.
    vec3 sum = previous * uCount + sample;
    FragColor = vec4(sum / (uCount + 1.0), 1.0);
}