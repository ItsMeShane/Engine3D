package particles;

/**
 * @param glow      brightness multiplier used with linear lighting: above 1 the particles are brighter than white
 *                  and bloom (fire, sparks); 1 for ordinary particles
 * @param waterFade above 0, the particles fade to nothing over this height above the water surface, so large soft
 *                  ones (mist) are not cut off in a hard line where they dip into the water; 0 = no fading
 */
public record ParticleTexture(int textureID, int numberOfRows, float glow, float waterFade) {

    public ParticleTexture(int textureID, int numberOfRows) {
        this(textureID, numberOfRows, 1, 0);
    }

    public ParticleTexture(int textureID, int numberOfRows, float glow) {
        this(textureID, numberOfRows, glow, 0);
    }
}
