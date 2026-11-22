#version 400 core

in vec2 position;

out vec4 clipSpace;
out vec2 textureCoords;
out vec3 toCameraVector;
out vec3 fromLightVector;

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;
uniform vec3 cameraPosition;
uniform vec3 lightPosition;

uniform float waterTiling; // ripple repeats per tileWorldSize (4 by default)
const float tileWorldSize = 200.0; // the ripple texture repeats 'tiling' times per this many world units

void main(void) {

	vec4 worldPosition = modelMatrix * vec4(position.x, 0.0, position.y, 1.0);
	vec4 positionRelativeToCamera = viewMatrix * worldPosition;
	clipSpace = projectionMatrix * positionRelativeToCamera;
	gl_Position = clipSpace;
	// world-space ripples: identical look for any tile size, and seamless across neighbouring tiles
	textureCoords = worldPosition.xz / tileWorldSize * waterTiling;
	toCameraVector = cameraPosition - worldPosition.xyz;
	fromLightVector = worldPosition.xyz - lightPosition;
	// fog is computed per fragment (waterFragment.glsl): with large tiles the corners are far away,
	// and interpolating a per-vertex fog value would fog the whole surface
}