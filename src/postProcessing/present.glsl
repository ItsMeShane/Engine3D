#version 330 core

// final pass to the window: bloom, tone mapping, gamma, colour grade

in vec2 uv;
out vec4 out_Colour;

uniform sampler2D scene;
uniform sampler2D bloomNear;
uniform sampler2D bloomWide;
uniform float linearLighting; // 1 = the scene buffer holds linear HDR colour
uniform float postEffects;    // 1 = bloom + grade + vignette
uniform float exposure;

// ACES filmic curve (Narkowicz fit)
vec3 toneMap(vec3 x) {
	return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main(void) {
	vec3 colour = texture(scene, uv).rgb;

	if (postEffects > 0.5) {
		colour += texture(bloomNear, uv).rgb * 0.55 + texture(bloomWide, uv).rgb * 0.75;
	}

	if (linearLighting > 0.5) {
		colour = toneMap(colour * exposure);
		colour = pow(colour, vec3(1.0 / 2.2));
	} else {
		colour = clamp(colour, 0.0, 1.0);
	}

	if (postEffects > 0.5) {
		float luma = dot(colour, vec3(0.2126, 0.7152, 0.0722));
		colour = mix(vec3(luma), colour, 1.12);                 // saturation
		colour = (colour - 0.5) * 1.06 + 0.5;                   // contrast
		vec2 fromCentre = uv - 0.5;
		colour *= 1.0 - dot(fromCentre, fromCentre) * 0.35;     // vignette
	}

	out_Colour = vec4(clamp(colour, 0.0, 1.0), 1.0);
}
