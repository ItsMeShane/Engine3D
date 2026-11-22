// Shared by the entity and terrain fragment shaders (pulled in with #include).
// Every feature is switched by a 0/1 uniform so it can be toggled at runtime (see GraphicsSettings).

uniform float linearLighting;   // light in linear colour; the final pass tone-maps and gamma-corrects
uniform float shadowsEnabled;
uniform float normalMapping;
uniform sampler2DShadow shadowMap;
uniform float shadowMapSize;

in vec4 shadowCoords;           // fragment position in the sun's shadow map (xy = texel, z = depth), 0..1
in vec3 worldPosition;

uniform vec3 ambientSky;        // light arriving from above (the sky)
uniform vec3 ambientGround;     // light bounced up from the ground

// colour textures and colour constants are stored gamma-encoded; lighting maths needs them linear
vec3 toLinear(vec3 colour) {
	return linearLighting > 0.5 ? pow(colour, vec3(2.2)) : colour;
}

// soft everywhere-light instead of a flat minimum brightness: surfaces facing up get sky, facing down get ground bounce
vec3 ambientLight(vec3 normal) {
	return mix(ambientGround, ambientSky, normal.y * 0.5 + 0.5);
}

// 1 = fully lit by the sun, 0 = in shadow. 3x3 percentage-closer filtering for soft edges.
float sunVisibility(float nDotL) {
	if (shadowsEnabled < 0.5) return 1.0;
	vec3 coords = shadowCoords.xyz;
	if (coords.z > 1.0 || coords.z < 0.0) return 1.0;
	float edge = max(abs(coords.x - 0.5), abs(coords.y - 0.5)) * 2.0;
	float fade = 1.0 - smoothstep(0.86, 1.0, edge);          // shadows fade out toward the border of the map
	if (fade <= 0.0) return 1.0;
	float bias = mix(0.0030, 0.0008, clamp(nDotL, 0.0, 1.0)); // surfaces at a grazing angle need more
	float texel = 1.0 / shadowMapSize;
	float lit = 0.0;
	for (int x = -1; x <= 1; x++) {
		for (int y = -1; y <= 1; y++) {
			lit += texture(shadowMap, vec3(coords.xy + vec2(x, y) * texel, coords.z - bias));
		}
	}
	return mix(1.0, lit / 9.0, fade);
}

// Tangent frame from screen-space derivatives, so meshes need no tangent attribute.
// Columns: direction of +u, direction of +v (downward in the image), surface normal.
mat3 cotangentFrame(vec3 normal, vec3 position, vec2 uv) {
	vec3 dp1 = dFdx(position), dp2 = dFdy(position);
	vec2 duv1 = dFdx(uv), duv2 = dFdy(uv);
	vec3 dp2perp = cross(dp2, normal), dp1perp = cross(normal, dp1);
	vec3 tangent = dp2perp * duv1.x + dp1perp * duv2.x;
	vec3 bitangent = dp2perp * duv1.y + dp1perp * duv2.y;
	float invMax = inversesqrt(max(max(dot(tangent, tangent), dot(bitangent, bitangent)), 1e-12));
	return mat3(tangent * invMax, bitangent * invMax, normal);
}
