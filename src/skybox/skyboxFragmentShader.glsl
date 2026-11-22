#version 400

in vec3 textureCoords;
out vec4 out_Color;

uniform samplerCube cubeMap1;
uniform samplerCube cubeMap2;
uniform float blendFactor;
uniform vec3 fogColor;
uniform float linearLighting;

const float lowerLimit = 0.0;
uniform float upperLimit; // the sky fades into the fog colour below this height (30 by default)

vec3 toLinear(vec3 colour) {
    return linearLighting > 0.5 ? pow(colour, vec3(2.2)) : colour;
}

void main(void){
    vec4 texture1 = texture(cubeMap1, textureCoords);
    vec4 texture2 = texture(cubeMap2, textureCoords);
    vec4 finalColor = mix(texture1, texture2, blendFactor);
    finalColor.rgb = toLinear(finalColor.rgb);
    if (linearLighting > 0.5) {
        // the photo clips at white where the sun is: push that area above 1 so it glows through the bloom
        float brightness = max(finalColor.r, max(finalColor.g, finalColor.b));
        finalColor.rgb *= 1.0 + 2.5 * smoothstep(0.82, 1.0, brightness);
    }

    float factor = (textureCoords.y - lowerLimit) / (upperLimit - lowerLimit);
    factor = clamp(factor, 0.0, 1.0);
    out_Color = mix(vec4(toLinear(fogColor), 1.0), finalColor, factor);
}
