#version 400

in vec2 pass_textureCoordinates;
in vec3 surfaceNormal;
in vec3 toLightVector[3];
in vec3 toCameraVector;
in float visibility;

out vec4 out_Color;

uniform sampler2D backgroundTexture;
uniform sampler2D rTexture;
uniform sampler2D gTexture;
uniform sampler2D bTexture;
uniform sampler2D blendMap;

// one normal map per ground texture (DirectX convention: green points along +v)
uniform sampler2D backgroundNormal;
uniform sampler2D rNormal;
uniform sampler2D gNormal;
uniform sampler2D bNormal;
uniform float hasNormalMaps;

uniform float terrainDetail;    // hide the tiling grid by mixing each texture at two scales

// low-poly ground (Environment.terrainFlatShading and friends); all three do nothing at their defaults
uniform float flatShading;      // light each triangle as one facet, ignoring the smoothed normals and the normal maps
uniform float facetVariation;   // random brightness difference from facet to facet
uniform vec3 extraGroundColour; // fifth ground colour, shown where the blend map's alpha is below 1
uniform float fogDensity;

uniform vec3 lightColor[3];
uniform vec3 attenuation[3];
uniform float shineDamper;
uniform float reflectivity;
uniform vec3 skyColor;

#include "shaders/glsl/lighting.glsl"

uniform float tiling;               // texture repeats per terrain tile
const float FAR_SCALE = 0.31;       // deliberately not a neat fraction, so the two grids never line up

float luminance(vec3 colour) {
    return dot(colour, vec3(0.2126, 0.7152, 0.0722));
}

// A ground texture sampled so that neither close-up detail nor distant repetition shows:
// near the camera the normal tiling, far away a ~3x larger copy, and everywhere a gentle
// light/dark variation taken from a blurred, much larger copy of the same texture.
vec3 ground(sampler2D tex, vec2 tiled, float distanceBlend) {
    vec3 near = texture(tex, tiled).rgb;
    if (terrainDetail < 0.5) return near;
    vec3 far = texture(tex, tiled * FAR_SCALE).rgb;
    vec3 average = textureLod(tex, tiled, 20.0).rgb;                       // smallest mip = the texture's mean colour
    float macro = luminance(texture(tex, tiled * 0.047, 2.5).rgb) / max(luminance(average), 0.001); // bias 2.5 = blurred
    return mix(near, far, distanceBlend) * mix(1.0, macro, 0.45);
}

void main(void) {

    vec4 blendMapColor = texture(blendMap, pass_textureCoordinates);

    float backTextureAmount = 1 - (blendMapColor.r + blendMapColor.g + blendMapColor.b);
    vec2 tiledCoordinates = pass_textureCoordinates * tiling;
    float distanceBlend = smoothstep(90.0, 420.0, length(toCameraVector)) * 0.7;

    vec3 groundColor = ground(backgroundTexture, tiledCoordinates, distanceBlend) * backTextureAmount
                     + ground(rTexture, tiledCoordinates, distanceBlend) * blendMapColor.r
                     + ground(gTexture, tiledCoordinates, distanceBlend) * blendMapColor.g
                     + ground(bTexture, tiledCoordinates, distanceBlend) * blendMapColor.b;
    groundColor = mix(extraGroundColour, groundColor, blendMapColor.a);
    vec4 totalColor = vec4(toLinear(groundColor), 1.0);

    vec3 unitNormal = normalize(surfaceNormal);
    vec3 shadowNormal = unitNormal;
    float fogVisibility = visibility;
    if (flatShading > 0.5) {
        // the triangle's own normal, from how the world position changes across the screen
        unitNormal = normalize(cross(dFdx(worldPosition), dFdy(worldPosition)));
        if (unitNormal.y < 0.0) unitNormal = -unitNormal;
        shadowNormal = unitNormal;
        // a cheap integer hash of the triangle's number: neighbouring facets differ a little, like hand-painted ones
        uint n = uint(gl_PrimitiveID) * 747796405u + 2891336453u;
        n = ((n >> ((n >> 28u) + 4u)) ^ n) * 277803737u;
        n = (n >> 22u) ^ n;
        totalColor.rgb *= 1.0 + (float(n & 0xffffu) / 65535.0 - 0.5) * 2.0 * facetVariation;
        // facets are large: fog per pixel, not interpolated from the corners
        fogVisibility = clamp(exp(-pow(length(toCameraVector) * fogDensity, 5.0)), 0.0, 1.0);
    } else if (hasNormalMaps > 0.5 && normalMapping > 0.5) {
        vec3 mapped = texture(backgroundNormal, tiledCoordinates).rgb * backTextureAmount
                    + texture(rNormal, tiledCoordinates).rgb * blendMapColor.r
                    + texture(gNormal, tiledCoordinates).rgb * blendMapColor.g
                    + texture(bNormal, tiledCoordinates).rgb * blendMapColor.b;
        mapped = mapped * 2.0 - 1.0;
        mapped.xy *= 1.0 - smoothstep(150.0, 420.0, length(toCameraVector)); // flatten far away, where it would only shimmer
        // texture u runs along world +x and v along world +z
        vec3 tangent = normalize(vec3(1.0, 0.0, 0.0) - unitNormal * unitNormal.x);
        vec3 bitangent = normalize(cross(tangent, unitNormal));
        unitNormal = normalize(tangent * mapped.x + bitangent * mapped.y + unitNormal * mapped.z);
    }
    vec3 unitVectorToCamera = normalize(toCameraVector);

    vec3 totalDiffuse = vec3(0.0);
    vec3 totalSpecular = vec3(0.0);

    for (int i = 0; i < 3; i++) {
        float distance = length(toLightVector[i]);
        float attenuationFactor = attenuation[i].x + (attenuation[i].y * distance) + (attenuation[i].z * distance * distance);
        vec3 unitLightVector = normalize(toLightVector[i]);
        float nDot1 = dot(unitNormal, unitLightVector);
        float brightness = max(nDot1, 0.0);
        vec3 lightDirection = -unitLightVector;
        vec3 reflectedLightDirection = reflect(lightDirection, unitNormal);
        float specularFactor = dot(reflectedLightDirection, unitVectorToCamera);
        specularFactor = max(specularFactor, 0.0);
        float dampedFactor = pow(specularFactor, shineDamper);
        float lit = i == 0 ? sunVisibility(dot(shadowNormal, unitLightVector)) : 1.0; // only the sun casts shadows
        totalDiffuse += lit * (brightness * lightColor[i] / attenuationFactor);
        totalSpecular += lit * (dampedFactor * reflectivity * lightColor[i]) / attenuationFactor;
    }
    if (linearLighting > 0.5) {
        totalDiffuse += ambientLight(unitNormal);
    } else {
        totalDiffuse = max(totalDiffuse, 0.7); // min brightness 0-1
    }


    out_Color = vec4(totalDiffuse, 1.0) * totalColor + vec4(totalSpecular, 1.0);
    out_Color = mix(vec4(toLinear(skyColor), 1.0), out_Color, fogVisibility);

}
