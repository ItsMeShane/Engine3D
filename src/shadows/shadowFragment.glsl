#version 330 core

// depth only; cut-out textures (foliage cards) must not cast their transparent parts

in vec2 uv;

uniform sampler2D modelTexture;
uniform float cutout;

void main(void) {
	if (cutout > 0.5 && texture(modelTexture, uv).a < 0.5) discard;
}
