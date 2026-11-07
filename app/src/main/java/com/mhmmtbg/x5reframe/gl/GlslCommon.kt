package com.mhmmtbg.x5reframe.gl

/** Kadraj ve dikiş tahmini shader'larının ortak lens fonksiyonları. */
object GlslCommon {
    const val LENS = """
vec2 project(vec3 c, vec4 intr, vec4 d1, vec2 d2) {
    c = normalize(c);
    vec2 m = c.xy / (c.z + d2.y);
    float r2 = dot(m, m);
    float rad = 1.0 + d1.x * r2 + d1.y * r2 * r2 + d1.z * r2 * r2 * r2;
    float p1 = d1.w;
    float p2 = d2.x;
    vec2 md = m * rad + vec2(2.0 * p1 * m.x * m.y + p2 * (r2 + 2.0 * m.x * m.x),
                             2.0 * p2 * m.x * m.y + p1 * (r2 + 2.0 * m.y * m.y));
    return vec2(intr.x * md.x + intr.z, intr.y * md.y + intr.w);
}

vec2 texCoord(mat4 texMat, vec4 sub, vec2 uv) {
    vec2 q = vec2(sub.x * uv.x + sub.y, sub.z * uv.y + sub.w);
    return (texMat * vec4(q.x, 1.0 - q.y, 0.0, 1.0)).xy;
}
"""
}
