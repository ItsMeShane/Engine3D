#version 400

in vec3 position;
in vec2 textureCoordinates;
in vec3 normal;

out vec2 pass_textureCoordinates;
out vec3 surfaceNormal;
out vec3 toLightVector[3];
out vec3 toCameraVector;
out float visibility;
out vec4 shadowCoords;
out vec3 worldPosition;

uniform mat4 transformationMatrix;
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 toShadowMapSpace;
uniform vec3 lightPosition[3];

uniform float useFakeLighting;

uniform float numberOfRows;
uniform vec2 offset;

// fog
uniform float fogDensity; // 0.002 by default (Environment)
const float gradient = 5;

uniform vec4 plane;

#include "shaders/glsl/wind.glsl"

void main(void){

    vec4 worldPos = transformationMatrix * vec4(position,1.0);
    worldPos.xyz = applyWind(worldPos.xyz, transformationMatrix);
    worldPosition = worldPos.xyz;
    shadowCoords = toShadowMapSpace * worldPos;

    gl_ClipDistance[0] = dot(worldPos, plane);

	vec4 positionRelativeToCamera = viewMatrix * worldPos;
	gl_Position = projectionMatrix * positionRelativeToCamera;
	pass_textureCoordinates = (textureCoordinates / numberOfRows + offset);

    vec3 actualNormal = normal;
    if (useFakeLighting > 0.5) {
        actualNormal = vec3(0.0, 1, 0.0);
    }

	surfaceNormal = (transformationMatrix * vec4(actualNormal, 0.0)).xyz;
    for (int i = 0; i < 3; i++) {
        toLightVector[i] = lightPosition[i] - worldPos.xyz;
    }
	toCameraVector = (inverse(viewMatrix) * vec4(0.0, 0.0, 0.0, 1.0)).xyz - worldPos.xyz;

    float distance = length(positionRelativeToCamera.xyz);
    visibility = exp(-pow((distance * fogDensity), gradient));
    visibility = clamp(visibility, 0.0, 1.0);
}
