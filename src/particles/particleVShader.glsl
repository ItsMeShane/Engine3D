#version 140

in vec2 position;

in mat4 modelViewMatrix;
in vec4 textureOffsets;
in float blendFactor;

out vec2 textureCoords1;
out vec2 textureCoords2;
out float blend;
out float worldHeight;


uniform mat4 projectionMatrix;
uniform float numberOfRows;
uniform mat4 inverseViewMatrix;


void main(void){

	vec2 textureCoords = position + vec2(0.5, 0.5);
	textureCoords.y = 1.0 - textureCoords.y;
	textureCoords /= numberOfRows;

	textureCoords1 = textureCoords + textureOffsets.xy;
	textureCoords2 = textureCoords + textureOffsets.zw;
	blend = blendFactor;

	vec4 viewPosition = modelViewMatrix * vec4(position, 0.0, 1.0);
	worldHeight = (inverseViewMatrix * viewPosition).y;
	gl_Position = projectionMatrix * viewPosition;

}