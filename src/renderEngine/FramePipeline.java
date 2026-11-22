package renderEngine;

import entities.Camera;
import entities.Entity;
import entities.Light;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Vector4f;
import particles.ParticleMaster;
import postProcessing.Fbo;
import postProcessing.PostProcessing;
import shadows.ShadowRenderer;
import terrains.Terrain;
import water.WaterFrameBuffers;
import water.WaterRenderer;
import water.WaterShader;
import water.WaterTile;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one complete frame:
 *   1. shadow map from the sun's point of view
 *   2. water reflection and refraction buffers
 *   3. main view (scene, water, particles) into an off-screen HDR buffer, multisampled when anti-aliasing is on
 *   4. post-processing to the window
 * Features are switched with {@link GraphicsSettings}.
 */
public class FramePipeline {

    private final MasterRenderer renderer;
    private final WaterFrameBuffers waterFrameBuffers = new WaterFrameBuffers();
    private final WaterRenderer waterRenderer;
    private final ShadowRenderer shadowRenderer = new ShadowRenderer();
    private final PostProcessing postProcessing;
    private final Fbo multisampledScene, scene;
    private Fbo output; // null = the window
    private final List<Entity> reflected = new ArrayList<>(), submerged = new ArrayList<>();

    public FramePipeline(MasterRenderer renderer) {
        this.renderer = renderer;
        waterRenderer = new WaterRenderer(new WaterShader(), renderer.getProjectionMatrix(), waterFrameBuffers);
        int width = DisplayManager.renderWidth(), height = DisplayManager.renderHeight();
        multisampledScene = new Fbo(width, height, GraphicsSettings.MSAA_SAMPLES);
        scene = new Fbo(width, height, 0);
        postProcessing = new PostProcessing(width, height);
    }

    /** Sends finished frames into a buffer instead of the window (null switches back), e.g. to save screenshots. */
    public void setOutput(Fbo output) {
        this.output = output;
    }

    /** @param sun the light that casts shadows and glints on the water (normally lights.get(0)) */
    public void render(List<Entity> entities, Terrain[][] terrains, List<Light> lights, Camera camera, List<WaterTile> waterTiles, Light sun) {
        // 1. shadows
        if (GraphicsSettings.shadows) {
            shadowRenderer.render(entities, terrains, sun, camera);
        }
        renderer.setShadows(shadowRenderer.getToShadowMapSpace(), shadowRenderer.getShadowMap());

        // 2. water: the scene mirrored below the surface, then the part under the surface.
        //    Fine ground cover (anything that casts no shadow) is left out of the mirror image, and only things
        //    standing in or near the water can show through its surface.
        float waterHeight = WaterTile.level();
        reflected.clear();
        submerged.clear();
        for (Entity entity : entities) {
            if (entity.getModel().texture().castsShadow()) reflected.add(entity);
            if (entity.getPosition().y < waterHeight + 12) submerged.add(entity);
        }
        GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
        waterFrameBuffers.bindReflectionFrameBuffer();
        float distance = 2 * (camera.getViewPointPosition().y - waterHeight);
        camera.getViewPointPosition().y -= distance;
        camera.invertPitch();
        renderer.renderScene(reflected, terrains, lights, camera, new Vector4f(0, 1, 0, -waterHeight + 3f));
        GL11.glDisable(GL30.GL_CLIP_DISTANCE0); // the particle shader has no clip plane
        ParticleMaster.renderParticles(camera); // fire, sparks and fireflies are mirrored in the water too
        GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
        camera.getViewPointPosition().y += distance;
        camera.invertPitch();
        waterFrameBuffers.bindRefractionFrameBuffer();
        renderer.renderScene(submerged, terrains, lights, camera, new Vector4f(0, -1, 0, waterHeight + 3f));
        GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
        waterFrameBuffers.unbindCurrentFrameBuffer();

        // 3. main view
        Fbo target = GraphicsSettings.antiAliasing ? multisampledScene : scene;
        target.bind();
        renderer.renderScene(entities, terrains, lights, camera, new Vector4f(0, 0, 0, 0));
        waterRenderer.render(waterTiles, camera, sun);
        ParticleMaster.renderParticles(camera);
        if (target == multisampledScene) multisampledScene.resolveTo(scene);
        Fbo.unbind();

        // 4. to the window
        postProcessing.present(scene.getColourTexture(), output);
    }

    public void cleanUp() {
        waterFrameBuffers.cleanUp();
        shadowRenderer.cleanUp();
        postProcessing.cleanUp();
        multisampledScene.cleanUp();
        scene.cleanUp();
    }
}
