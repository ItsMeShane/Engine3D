#version 400

in vec3 position;
out vec3 textureCoords;

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;

void main(void){

    // .xyww puts every vertex exactly on the far plane (depth = w / w = 1): the sky is always behind the scene
    // and no part of the cube can be cut off by the far plane, whatever its size (its corners used to be).
    gl_Position = (projectionMatrix * viewMatrix * vec4(position, 1.0)).xyww;
    textureCoords = position;

}