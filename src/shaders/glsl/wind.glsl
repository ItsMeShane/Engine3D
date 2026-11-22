// Shared by the entity and shadow vertex shaders (pulled in with #include): moves vertices with the wind.

uniform float time;
uniform vec2 wind;   // x = bend (whole plant leans, more the higher above its base), y = flutter (fast leaf motion); 0 = rigid

const vec2 WIND_DIRECTION = vec2(0.86, 0.5);

vec3 applyWind(vec3 worldPos, mat4 model) {
	if (wind.x <= 0.0 && wind.y <= 0.0) return worldPos;
	vec3 origin = model[3].xyz;                                   // where the plant stands
	float height = max(worldPos.y - origin.y, 0.0);
	float phase = origin.x * 0.031 + origin.z * 0.047;           // neighbours sway slightly out of step
	// gusts: a slow swell plus two faster waves, mostly leaning with the wind
	float gust = 0.35 + sin(time * 0.8 + phase) * 0.45 + sin(time * 1.9 + phase * 1.7 + 1.3) * 0.25 + sin(time * 0.23 + phase * 0.3) * 0.2;
	float bend = wind.x * pow(height * 0.1, 1.5) * gust;
	float reach = clamp(length(worldPos.xz - origin.xz) / 6.0, 0.0, 1.0); // leaves far from the stem move most
	vec3 flutter = vec3(sin(time * 3.7 + worldPos.x * 0.9 + worldPos.y * 0.7),
	                    sin(time * 4.3 + worldPos.z * 0.8 + worldPos.x * 0.5) * 0.6,
	                    sin(time * 3.1 + worldPos.z * 1.1 + worldPos.y * 0.6));
	float rooted = clamp(height * 0.4, 0.0, 1.0);                // nothing moves where the plant meets the ground
	return worldPos + vec3(WIND_DIRECTION.x * bend, 0.0, WIND_DIRECTION.y * bend) + flutter * (wind.y * reach * rooted);
}
