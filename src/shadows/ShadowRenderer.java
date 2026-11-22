package shadows;

import entities.Camera;
import entities.Entity;
import entities.Light;
import models.RawModel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import postProcessing.Fbo;
import renderEngine.DisplayManager;
import shaders.ShaderProgram;
import terrains.Terrain;
import textures.ModelTexture;
import toolbox.Maths;

import java.nio.ByteBuffer;
import java.util.List;


public class ShadowRenderer {

    public static final int MAP_SIZE = 4096;
    /** Half-width of the shadowed box around the focus point, and its half-depth along the sun direction. */
    private static final float BOX_RADIUS = 330, BOX_DEPTH = 520;

    private static class ShadowShader extends ShaderProgram {
        ShadowShader() {
            super("shadows/shadowVertex.glsl", "shadows/shadowFragment.glsl");
        }

        @Override
        protected void getAllUniformLocations() {
        }

        @Override
        protected void bindAttributes() {
            bindAttribute(0, "position");
            bindAttribute(1, "textureCoordinates");
        }
    }

    private final ShadowShader shader = new ShadowShader();
    private final int frameBuffer, depthTexture;
    private final Matrix4f worldToShadowClip = new Matrix4f();
    private final Matrix4f toShadowMapSpace = new Matrix4f();

    public ShadowRenderer() {
        frameBuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, frameBuffer);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        depthTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, MAP_SIZE, MAP_SIZE, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // let the GPU do the depth comparison (and filter the result) when sampled through sampler2DShadow
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Shadow framebuffer incomplete: 0x" + Integer.toHexString(status));
        Fbo.unbind();
    }

    public void render(List<Entity> entities, Terrain[][] terrains, Light sun, Camera camera) {
        updateMatrices(sun, camera.getFocusPoint().getPosition());

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, frameBuffer);
        GL11.glViewport(0, 0, MAP_SIZE, MAP_SIZE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL); // push depths back a little: stops surfaces shadowing themselves
        GL11.glPolygonOffset(2f, 4f);

        shader.start();
        shader.set("modelTexture", 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        shader.set("shadowMatrix", worldToShadowClip);
        shader.set("time", DisplayManager.getTime());

        // terrain: positions only
        shader.set("cutout", false);
        shader.set("numberOfRows", 1f);
        shader.set("offset", 0, 0);
        shader.set("wind", 0, 0);
        for (Terrain[] row : terrains) {
            for (Terrain terrain : row) {
                shader.set("transformationMatrix", Maths.createTransformationMatrix(new Vector3f(terrain.getX(), 0, terrain.getZ()), 0, 0, 0, 1));
                draw(terrain.getModel());
            }
        }

        // entities
        for (Entity entity : entities) {
            ModelTexture texture = entity.getModel().texture();
            if (!texture.castsShadow()) continue;
            boolean cutout = texture.hasTransparency();
            if (cutout) {
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getID());
            }
            shader.set("cutout", cutout);
            shader.set("numberOfRows", (float) texture.getNumberOfRows());
            shader.set("offset", entity.getTextureXOffset(), entity.getTextureYOffset());
            shader.set("wind", texture.getWindBend(), texture.getWindFlutter());
            shader.set("transformationMatrix", Maths.createTransformationMatrix(entity.getPosition(), entity.getRotX(), entity.getRotY(), entity.getRotZ(), entity.getScale()));
            draw(entity.getModel().rawModel());
            if (cutout) GL11.glEnable(GL11.GL_CULL_FACE);
        }

        GL20.glDisableVertexAttribArray(0);
        GL20.glDisableVertexAttribArray(1);
        GL30.glBindVertexArray(0);
        shader.stop();
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        Fbo.unbind();
    }

    private void draw(RawModel model) {
        GL30.glBindVertexArray(model.vaoID());
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL11.glDrawElements(GL11.GL_TRIANGLES, model.vertexCount(), GL11.GL_UNSIGNED_INT, 0);
    }

    /**
     * World -> shadow clip space is an orthographic view along the sun direction, centred on the focus point.
     * The centre is snapped to whole shadow-map texels so shadow edges don't crawl when the camera moves.
     */
    private void updateMatrices(Light sun, Vector3f centre) {
        Vector3f toSun = Vector3f.sub(sun.getPosition(), centre, null);
        toSun.normalise();
        Vector3f right = Vector3f.cross(new Vector3f(0, 1, 0), toSun, null);
        if (right.lengthSquared() < 1e-6f) right.set(1, 0, 0); // sun straight overhead
        right.normalise();
        Vector3f up = Vector3f.cross(toSun, right, null);

        float texel = 2 * BOX_RADIUS / MAP_SIZE;
        float cx = (float) Math.floor(Vector3f.dot(right, centre) / texel) * texel;
        float cy = (float) Math.floor(Vector3f.dot(up, centre) / texel) * texel;
        float cz = Vector3f.dot(toSun, centre);

        Matrix4f m = worldToShadowClip;
        m.setIdentity();
        m.m00 = right.x / BOX_RADIUS;
        m.m10 = right.y / BOX_RADIUS;
        m.m20 = right.z / BOX_RADIUS;
        m.m30 = -cx / BOX_RADIUS;
        m.m01 = up.x / BOX_RADIUS;
        m.m11 = up.y / BOX_RADIUS;
        m.m21 = up.z / BOX_RADIUS;
        m.m31 = -cy / BOX_RADIUS;
        m.m02 = -toSun.x / BOX_DEPTH;   // closer to the sun = smaller depth
        m.m12 = -toSun.y / BOX_DEPTH;
        m.m22 = -toSun.z / BOX_DEPTH;
        m.m32 = cz / BOX_DEPTH;

        // clip space (-1..1) -> texture space (0..1)
        Matrix4f bias = new Matrix4f();
        bias.m00 = bias.m11 = bias.m22 = 0.5f;
        bias.m30 = bias.m31 = bias.m32 = 0.5f;
        Matrix4f.mul(bias, worldToShadowClip, toShadowMapSpace);
    }

    /** World position -> (shadow map u, v, depth), all in 0..1. */
    public Matrix4f getToShadowMapSpace() {
        return toShadowMapSpace;
    }

    public int getShadowMap() {
        return depthTexture;
    }

    public void cleanUp() {
        shader.cleanUp();
        GL30.glDeleteFramebuffers(frameBuffer);
        GL11.glDeleteTextures(depthTexture);
    }
}
