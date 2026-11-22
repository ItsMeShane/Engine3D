#version 400

in vec2 pass_textureCoordinates;
in vec3 surfaceNormal;
in vec3 toLightVector[3];
in vec3 toCameraVector;
in float visibility;

out vec4 out_Color;

uniform sampler2D modelTexture;
uniform sampler2D normalMap;
uniform float hasNormalMap;
uniform float twoSided;         // light back faces as if their normal pointed the other way (leaf geometry)
uniform float translucency;     // share of the light that passes through from behind (leaves)
uniform float emissive;         // adds texture colour * emissive (lantern glass); blooms when above 1
uniform vec3 lightColor[3];
uniform vec3 attenuation[3];
uniform float shineDamper;
uniform float reflectivity;
uniform vec3 skyColor;

#include "shaders/glsl/lighting.glsl"

void main(void){

    vec4 textureColor = texture(modelTexture, pass_textureCoordinates);
    if (textureColor.a < 0.5) {
        discard;
    }
    textureColor.rgb = toLinear(textureColor.rgb);

    vec3 unitNormal = normalize(surfaceNormal);
    if (twoSided > 0.5 && !gl_FrontFacing) unitNormal = -unitNormal;
    if (hasNormalMap > 0.5 && normalMapping > 0.5) {
        vec3 mapped = texture(normalMap, pass_textureCoordinates).rgb * 2.0 - 1.0;
        unitNormal = normalize(cotangentFrame(unitNormal, worldPosition, pass_textureCoordinates) * mapped);
    }
    vec3 unitVectorToCamera = normalize(toCameraVector);

    vec3 totalDiffuse = vec3(0.0);
    vec3 totalSpecular = vec3(0.0);

    for (int i = 0; i < 3; i++) {
        float distance = length(toLightVector[i]);
        float attenuationFactor = attenuation[i].x + (attenuation[i].y * distance) + (attenuation[i].z  * distance * distance);
        vec3 unitLightVector = normalize(toLightVector[i]);
        float nDot1 = dot(unitNormal, unitLightVector);
        float brightness = max(nDot1, 0.0) + translucency * max(-nDot1, 0.0);
        vec3 lightDirection = -unitLightVector;
        vec3 reflectedLightDirection = reflect(lightDirection, unitNormal);
        float specularFactor = dot(reflectedLightDirection, unitVectorToCamera);
        specularFactor = max(specularFactor, 0.0);
        float dampedFactor = pow(specularFactor, shineDamper);
        float lit = i == 0 ? sunVisibility(nDot1) : 1.0; // only the sun (first light) casts shadows
        totalDiffuse += lit * (brightness * lightColor[i] / attenuationFactor);
        totalSpecular += lit * (dampedFactor * reflectivity * lightColor[i]) / attenuationFactor;
    }
    if (linearLighting > 0.5) {
        totalDiffuse += ambientLight(unitNormal);
    } else {
        totalDiffuse = max(totalDiffuse, 0.4); // minimum brightness
    }

	out_Color = vec4(totalDiffuse, 1.0) * textureColor + vec4(totalSpecular, 1.0);
	out_Color.rgb += textureColor.rgb * emissive * linearLighting;
	out_Color = mix(vec4(toLinear(skyColor), 1.0), out_Color, visibility);

}
