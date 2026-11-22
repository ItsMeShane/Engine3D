#version 330 core

// keeps only what is brighter than the threshold: that is what blooms

in vec2 uv;
out vec4 out_Colour;

uniform sampler2D scene;
uniform float threshold;

void main(void) {
	vec3 colour = texture(scene, uv).rgb;
	float brightness = max(colour.r, max(colour.g, colour.b));
	float keep = max(brightness - threshold, 0.0) / max(brightness, 0.0001);
	out_Colour = vec4(min(colour * keep, vec3(8.0)), 1.0);
}
