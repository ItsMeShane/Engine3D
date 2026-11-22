#version 140

out vec4 out_colour;

in vec2 textureCoords1;
in vec2 textureCoords2;
in float blend;
in float worldHeight;

uniform sampler2D particleTexture;
uniform float linearLighting;
uniform float waterLevel;
uniform float waterFade; // > 0: the particle fades to nothing over this height above the water (0 = off)
uniform float glow; // brightness multiplier in linear mode: above 1 the particle blooms (fire, sparks)

void main(void){

	vec4 color1 = texture(particleTexture, textureCoords1);
	vec4 color2 = texture(particleTexture, textureCoords2);

	out_colour = mix(color1, color2, blend);
	// big soft particles such as mist would otherwise be cut off in a hard line where they dip into the water
	if (waterFade > 0.0) out_colour.a *= smoothstep(waterLevel, waterLevel + waterFade, worldHeight);
	if (linearLighting > 0.5) {
		out_colour.rgb = pow(out_colour.rgb, vec3(2.2)) * glow;
	}

}
