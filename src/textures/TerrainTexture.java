package textures;

/** A ground texture and, optionally, its normal map (0 = none). */
public record TerrainTexture(int textureID, int normalMapID) {

    public TerrainTexture(int textureID) {
        this(textureID, 0);
    }
}
