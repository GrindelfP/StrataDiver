#version 330 core
in vec2 TexCoord;
uniform sampler2D uCurrent;
uniform sampler2D uCurrentNormalDepth;
uniform sampler2D uCurrentAlbedo;
uniform sampler2D uCurrentPosition;
uniform sampler2D uHistory;
uniform sampler2D uPreviousNormalDepth;
uniform sampler2D uPreviousAlbedo;
uniform sampler2D uPreviousPosition;
uniform mat4 uPreviousViewProjection;
uniform vec2 uTexel;
uniform float uAlpha;
uniform int uHistoryValid;
uniform int uDebug;
layout(location=0) out vec4 oHistory;
layout(location=1) out vec4 oNormalDepth;
layout(location=2) out vec4 oAlbedo;
layout(location=3) out vec4 oPosition;

void main() {
    vec4 nd = texture(uCurrentNormalDepth, TexCoord);
    vec4 pos = texture(uCurrentPosition, TexCoord);
    vec3 curr = texture(uCurrent, TexCoord).rgb;
    vec2 oldUv = vec2(-1.0);
    bool valid = uHistoryValid != 0 && nd.w > 0.0 && pos.w > 0.5;
    if (valid) {
        vec4 clip = uPreviousViewProjection * vec4(pos.xyz, 1.0);
        valid = clip.w > 0.0;
        if (valid) {
            oldUv = clip.xy / clip.w * 0.5 + 0.5;
            valid = all(greaterThanEqual(oldUv, vec2(0.0))) && all(lessThanEqual(oldUv, vec2(1.0)));
        }
    }
    vec3 hist = curr;
    if (valid) {
        vec4 oldNd = texture(uPreviousNormalDepth, oldUv);
        vec4 oldPos = texture(uPreviousPosition, oldUv);
        vec3 oldAlb = texture(uPreviousAlbedo, oldUv).rgb;
        vec3 curAlb = texture(uCurrentAlbedo, TexCoord).rgb;
        float tolerance = max(0.04, nd.w * 0.012);
        valid = oldNd.w > 0.0 && oldPos.w > 0.5 &&
                distance(oldPos.xyz, pos.xyz) <= tolerance &&
                dot(normalize(oldNd.xyz), normalize(nd.xyz)) > 0.92 &&
                distance(oldAlb, curAlb) < 0.18;
        if (valid) hist = texture(uHistory, oldUv).rgb;
    }

    // Neighborhood clamp prevents a valid but noisy correspondence importing an outlier.
    if (valid) {
        vec3 lo = curr, hi = curr;
        for (int y=-1; y<=1; ++y) for (int x=-1; x<=1; ++x) {
            vec3 c = texture(uCurrent, TexCoord + vec2(x,y)*uTexel).rgb;
            lo = min(lo,c); hi = max(hi,c);
        }
        hist = clamp(hist, lo, hi);
    }
    float historyWeight = valid ? (1.0-uAlpha) : 0.0;
    vec3 result = mix(curr, hist, historyWeight);
    oHistory = vec4(uDebug != 0 ? (valid ? vec3(0,1,0) : vec3(1,0,0)) : result, 1.0);
    oNormalDepth = nd;
    oAlbedo = texture(uCurrentAlbedo, TexCoord);
    oPosition = pos;
}
