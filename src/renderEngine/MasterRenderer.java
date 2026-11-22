package renderEngine;

import entities.Camera;
import entities.Entity;
import entities.Light;
import models.TexturedModel;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector4f;
import shaders.ShaderProgram;
import shaders.StaticShader;
import shadows.ShadowRenderer;
import shaders.TerrainShader;
import skybox.SkyboxRenderer;
import terrains.Terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MasterRenderer {

    private static final float NEAR_PLANE = 0.1f; // how close you can see; how far is Environment.farPlane



    private Matrix4f projectionMatrix;

    private final StaticShader entityShader = new StaticShader();


    private final EntityRenderer entityRenderer;
    private final TerrainRenderer terrainRenderer;
    private final SkyboxRenderer skyboxRenderer;
    private final TerrainShader terrainShader = new TerrainShader();

    private final Map<TexturedModel, List<Entity>> entities = new HashMap<>();
    private final List<Terrain> terrains = new ArrayList<>();

    /** Texture unit the shadow map of the sun is bound to for both scene shaders. */
    public static final int SHADOW_MAP_UNIT = 5;
    private Matrix4f toShadowMapSpace = new Matrix4f();
    private int shadowMap;

    public MasterRenderer() {
        enableCulling();
        createProjectionMatrix();
        entityRenderer = new EntityRenderer(entityShader, projectionMatrix);
        terrainRenderer = new TerrainRenderer(terrainShader, projectionMatrix);
        skyboxRenderer = new SkyboxRenderer(projectionMatrix);

        entityShader.start();
        entityShader.set("modelTexture", 0);
        entityShader.set("normalMap", 1);
        entityShader.set("shadowMap", SHADOW_MAP_UNIT);
        entityShader.stop();
        terrainShader.start();
        terrainShader.set("shadowMap", SHADOW_MAP_UNIT);
        terrainShader.stop();
    }

    /** Shadow map of the sun and the matrix that takes world positions into it (see ShadowRenderer). */
    public void setShadows(Matrix4f toShadowMapSpace, int shadowMap) {
        this.toShadowMapSpace = toShadowMapSpace;
        this.shadowMap = shadowMap;
    }

    /** Per-pass uniforms shared by the entity and terrain shaders (shaders/glsl/lighting.glsl). */
    private void loadLightingSettings(ShaderProgram shader) {
        shader.set("linearLighting", GraphicsSettings.linearLighting);
        shader.set("shadowsEnabled", GraphicsSettings.shadows);
        shader.set("normalMapping", GraphicsSettings.normalMapping);
        shader.set("shadowMapSize", (float) ShadowRenderer.MAP_SIZE);
        shader.set("toShadowMapSpace", toShadowMapSpace);
        shader.set("fogDensity", Environment.fogDensity);
        shader.set("ambientSky", Environment.ambientSky);
        shader.set("ambientGround", Environment.ambientGround);
    }

    public void renderScene(List<Entity> entities, Terrain[][] terrains, List<Light> lights, Camera camera, Vector4f clipPlane) {

        // terrains
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                processTerrain(terrains[j][i]);

        // entities
        for (Entity entity : entities)
            processEntity(entity);

        render(lights, camera, clipPlane);

    }


    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    public static void enableCulling() { // dont render faces you cant see
        GL11.glEnable(GL11.GL_CULL_FACE);
    }
    public static void disableCulling() {
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    private void render(List<Light> lights, Camera camera, Vector4f clipPlane) {
        prepare();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_MAP_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowMap);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        // entities
        entityShader.start();
        loadLightingSettings(entityShader);
        entityShader.set("time", DisplayManager.getTime());
        entityShader.loadClipPlane(clipPlane);
        entityShader.loadSkyColor(Environment.fogColour.x, Environment.fogColour.y, Environment.fogColour.z);
        entityShader.loadLights(lights);
        entityShader.loadViewMatrix(camera);
        entityRenderer.render(entities);
        entityShader.stop();

        // terrain
        terrainShader.start();
        loadLightingSettings(terrainShader);
        terrainShader.set("terrainDetail", GraphicsSettings.terrainDetail);
        terrainShader.set("tiling", Environment.terrainTiling);
        terrainShader.set("flatShading", Environment.terrainFlatShading);
        terrainShader.set("facetVariation", Environment.terrainFacetVariation);
        terrainShader.set("extraGroundColour", Environment.terrainExtraColour);
        terrainShader.loadClipPlane(clipPlane);
        terrainShader.loadSkyColor(Environment.fogColour.x, Environment.fogColour.y, Environment.fogColour.z);
        terrainShader.loadLights(lights);
        terrainShader.loadViewMatrix(camera);
        terrainRenderer.render(terrains);
        terrainShader.stop();
        if (true) skyboxRenderer.render(camera, Environment.fogColour.x, Environment.fogColour.y, Environment.fogColour.z);

        terrains.clear();
        entities.clear();
    }

    public void processTerrain(Terrain terrain) {
        terrains.add(terrain);
    }

    public void processEntity(Entity entity) {
        TexturedModel entityModel = entity.getModel();
        List<Entity> batch = entities.get(entityModel);
        if (batch != null) {
            batch.add(entity);
        } else {
            List<Entity> newBatch = new ArrayList<>();
            newBatch.add(entity);
            entities.put(entityModel, newBatch);
        }
    }

    public void cleanUp() {
        entityShader.cleanUp();
        terrainShader.cleanUp();
    }

    public void prepare() {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
        if (GraphicsSettings.linearLighting) { // the buffer holds linear colour: store the sky colour the same way
            GL11.glClearColor((float) Math.pow(Environment.fogColour.x, 2.2), (float) Math.pow(Environment.fogColour.y, 2.2), (float) Math.pow(Environment.fogColour.z, 2.2), 1);
        } else {
            GL11.glClearColor(Environment.fogColour.x, Environment.fogColour.y, Environment.fogColour.z, 1);
        }
    }

    public void createProjectionMatrix(){
        float aspectRatio = (float) DisplayManager.renderWidth() / (float) DisplayManager.renderHeight();
        float y_scale = (float) ((1f / Math.tan(Math.toRadians(Camera.FOV / 2f))) * aspectRatio);
        float x_scale = y_scale / aspectRatio;
        float farPlane = Environment.farPlane;
        float frustum_length = farPlane - NEAR_PLANE;

        projectionMatrix = new Matrix4f();
        projectionMatrix.m00 = x_scale;
        projectionMatrix.m11 = y_scale;
        projectionMatrix.m22 = -((farPlane + NEAR_PLANE) / frustum_length);
        projectionMatrix.m23 = -1;
        projectionMatrix.m32 = -((2 * NEAR_PLANE * farPlane) / frustum_length);
        projectionMatrix.m33 = 0;
    }

}
