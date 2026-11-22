#version 330 core

in vec3 position;
in vec2 textureCoordinates;

out vec2 uv;

uniform mat4 transformationMatrix;
uniform mat4 shadowMatrix;      // world -> shadow clip space
uniform float numberOfRows;
uniform vec2 offset;

#include "shaders/glsl/wind.glsl"

void main(void) {
	vec4 worldPos = transformationMatrix * vec4(position, 1.0);
	gl_Position = shadowMatrix * vec4(applyWind(worldPos.xyz, transformationMatrix), 1.0); // shadows sway with their plants
	uv = textureCoordinates / numberOfRows + offset;
}
