package engineTester;

import entities.Camera;
import entities.Entity;
import entities.Light;
import org.lwjgl.util.vector.Vector3f;
import terrains.HeightSource;
import terrains.Terrain;
import textures.TerrainTexture;
import textures.TerrainTexturePack;
import water.WaterTile;

import java.util.List;

/**
 * Everything that differs from one scene to the next: the ground textures, what stands on the terrain,
 * the lights, the water, the opening camera and any per-frame motion. {@link MainGameLoop} runs whichever
 * scene {@link Scenes} selects.
 *
 * Terrain shape, water level and the environment (sky, fog, ambient light) have defaults that a scene can override.
 */
public interface GameScene {

    /** The shape of the ground; null = the engine's default procedural hills. */
    default HeightSource terrainHeights() {
        return null;
    }

    /** Height of the water surface. */
    default float waterLevel() {
        return WaterTile.WATER_HEIGHT;
    }

    /**
     * Changes sky, fog, ambient light or texture scales in {@link renderEngine.Environment}. Runs first, before the
     * renderer loads anything; the defaults are the original engine look.
     */
    default void configureEnvironment() {
    }

    /** Background + red/green/blue ground textures, picked per pixel by the blend maps. */
    TerrainTexturePack terrainTextures();

    /** Blend map of the terrain tile whose world origin is (tileX * Terrain.SIZE, tileZ * Terrain.SIZE). */
    TerrainTexture blendMap(int tileX, int tileZ);

    /** Adds the scene's entities, lights and water tiles. Called once, before the Editor is created. */
    void populate(Terrain[][] terrains, List<Entity> entities, List<Light> lights, List<WaterTile> waterTiles);

    /** Opening shot. */
    void setUpCamera(Camera camera);

    /** Called every frame: animations and ambient particle effects. */
    void update();

    /** The directional light that casts shadows and glints on the water. Must also be the first light added in populate. */
    Light getSun();

    /** Called while an entity is dragged with the right mouse button, e.g. to move a light along with its lamp. */
    default void entityDragged(Entity entity, Vector3f position, List<Light> lights) {
    }

    /**
     * Tells the scene which b-roll shot is being played or recorded and how far into it the next frame is, before
     * {@link #update()} runs for that frame; (null, 0) when playback stops. The world clock at the start of a shot
     * differs between live playback, a full recording, a single-shot recording and preview stills, so anything
     * that moves through the picture and must be where the camera expects it (a boat) should be placed from this
     * time instead of the world clock while its shot runs.
     */
    default void shotTime(String shotName, float seconds) {
    }

    /**
     * Seconds the recorder should run the scene, unfilmed, before the first frame of this shot, counting
     * {@link #shotTime} up from minus this value to zero. For things that leave a trail (a wake behind a boat)
     * which must already be there when the shot starts. 0 = none.
     */
    default float shotLeadIn(String shotName) {
        return 0;
    }
}
