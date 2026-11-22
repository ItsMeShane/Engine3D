#version 400 core

in vec4 clipSpace;
in vec2 textureCoords;
in vec3 toCameraVector;
in vec3 fromLightVector;

out vec4 out_Color;

uniform sampler2D reflectionTexture;
uniform sampler2D refractionTexture;
uniform sampler2D dudvMap;
uniform sampler2D normalMap;
uniform sampler2D depthMap;
uniform vec3 lightColour;

uniform float moveFactor;

uniform vec3 skyColor;

uniform float linearLighting; // the buffers hold linear colour; colour constants must be converted to match
uniform float waterGlint;     // strength of the sun sparkle in linear mode (3 by default)
uniform float waterEffects;   // soft shores, depth tint, fresnel reflection
uniform float waterMurkiness; // how quickly the bed fades with depth (Environment.waterMurkiness, 0.10 by default)
uniform vec3 waterDeepColour; // what it fades into (Environment.waterDeepColour)
uniform float farPlane;       // the camera's far plane (Environment.farPlane, 750 by default)

const float waveStrength = 0.03;
const float shineDamper = 20.0;
const float reflectivity = 0.5;

// fog (same curve as the entity/terrain shaders)
uniform float fogDensity;
const float gradient = 5;

const vec3 SHALLOW_TINT = vec3(0.0, 0.3, 0.5);

vec3 toLinear(vec3 colour) {
	return linearLighting > 0.5 ? pow(colour, vec3(2.2)) : colour;
}

void main(void) {

	vec2 ndc = (clipSpace.xy / clipSpace.w) / 2.0 + 0.5;
	vec2 refractTexCoords = vec2(ndc.x, ndc.y);
	vec2 reflectTexCoords = vec2(ndc.x, -ndc.y);

	float near = 0.1;
	float far = waterEffects > 0.5 ? farPlane : 1000.0; // the real far plane; 1000 was the old approximation
	float depth = texture(depthMap, refractTexCoords).r;
	float floorDistance = 2.0 * near * far / (far + near - (2.0 * depth - 1.0) * (far - near));

	depth = gl_FragCoord.z;
	float waterDistance = 2.0 * near * far / (far + near - (2.0 * depth - 1.0) * (far - near));
	float waterDepth = floorDistance - waterDistance;

	vec2 distortedTexCoords = texture(dudvMap, vec2(textureCoords.x + moveFactor, textureCoords.y)).rg * 0.1;
	distortedTexCoords = textureCoords + vec2(distortedTexCoords.x, distortedTexCoords.y + moveFactor);
	vec2 totalDistortion = (texture(dudvMap, distortedTexCoords).rg * 2.0 - 1.0) * waveStrength * clamp(waterDepth / 20.0, 0.0, 1.0);

	refractTexCoords += totalDistortion;
	refractTexCoords = clamp(refractTexCoords, 0.001, 0.999);

	reflectTexCoords += totalDistortion;
	reflectTexCoords.x = clamp(reflectTexCoords.x, 0.001, 0.999);
	reflectTexCoords.y = clamp(reflectTexCoords.y, -0.999, -0.001);

	vec4 reflectColour = texture(reflectionTexture, reflectTexCoords);
	vec4 refractColour = texture(refractionTexture, refractTexCoords);

	vec4 normalMapColour = texture(normalMap, distortedTexCoords);
	vec3 normal = vec3(normalMapColour.r * 2.0 - 1.0, normalMapColour.b * 3.0, normalMapColour.g * 2.0 - 1.0);
	normal = normalize(normal);

	vec3 viewVector = normalize(toCameraVector);
	vec3 reflectedLight = reflect(normalize(fromLightVector), normal);
	float specular = max(dot(reflectedLight, viewVector), 0.0);
	specular = pow(specular, shineDamper);

	if (waterEffects > 0.5) {
		// water absorbs light: the deeper the bed, the more it fades into the deep-water colour
		float murk = 1.0 - exp(-max(waterDepth, 0.0) * waterMurkiness);
		refractColour.rgb = mix(refractColour.rgb, toLinear(waterDeepColour), murk * 0.85);

		// fresnel: looking straight down you see into the water, at a shallow angle you see the reflection
		float facing = clamp(dot(viewVector, normal), 0.0, 1.0);
		float reflectAmount = 0.04 + 0.96 * pow(1.0 - facing, 3.0);
		out_Color = mix(refractColour, reflectColour, reflectAmount);

		// sun glints; in linear mode they go well above 1 so the bloom picks them up
		float glintStrength = linearLighting > 0.5 ? waterGlint : reflectivity;
		out_Color.rgb += lightColour * pow(specular, 3.0) * glintStrength * clamp(waterDepth / 4.0, 0.0, 1.0); // pow: tight sparkles

		// soft shoreline: the water thins out to nothing where it meets the land
		out_Color.a = clamp(waterDepth / 1.6, 0.0, 1.0);
	} else {
		float refractiveFactor = dot(viewVector, normal);
		refractiveFactor = pow(refractiveFactor, 0.5);
		refractiveFactor = clamp(refractiveFactor, 0.0, 1.0);
		vec3 specularHighlights = lightColour * specular * reflectivity * clamp(waterDepth / 5.0, 0.0, 1.0);

		out_Color = mix(reflectColour, refractColour, refractiveFactor);
		out_Color = mix(out_Color, vec4(toLinear(SHALLOW_TINT), 1.0), 0.2) + vec4(specularHighlights, 0.0);
		out_Color.a = 1.0;
	}

	float visibility = clamp(exp(-pow(length(toCameraVector) * fogDensity, gradient)), 0.0, 1.0);
	out_Color.rgb = mix(toLinear(skyColor), out_Color.rgb, visibility);

}
