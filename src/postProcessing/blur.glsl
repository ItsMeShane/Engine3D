#version 330 core

// one direction of a separable 9-tap Gaussian blur

in vec2 uv;
out vec4 out_Colour;

uniform sampler2D source;
uniform vec2 texelStep; // (1/width, 0) or (0, 1/height) of the source

const float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);

void main(void) {
	vec3 sum = texture(source, uv).rgb * weights[0];
	for (int i = 1; i < 5; i++) {
		sum += texture(source, uv + texelStep * float(i)).rgb * weights[i];
		sum += texture(source, uv - texelStep * float(i)).rgb * weights[i];
	}
	out_Colour = vec4(sum, 1.0);
}
