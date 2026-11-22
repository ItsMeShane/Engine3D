package postProcessing;

import models.RawModel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import renderEngine.GraphicsSettings;
import renderEngine.Loader;
import shaders.ShaderProgram;

/**
 * Takes the finished scene (a texture of linear, possibly brighter-than-white colour) and puts it on the
 * window: bright areas are extracted and blurred at half and quarter resolution for bloom, then one pass
 * combines everything, tone-maps, gamma-corrects and grades.
 */
public class PostProcessing {

    private static final float BLOOM_THRESHOLD = 1.5f;
    private static final float EXPOSURE = 0.9f;

    /** A program that draws the full-screen quad; uniforms are set by name. */
    private static class QuadShader extends ShaderProgram {
        QuadShader(String fragmentFile) {
            super("postProcessing/quadVertex.glsl", fragmentFile);
        }

        @Override
        protected void getAllUniformLocations() {
        }

        @Override
        protected void bindAttributes() {
            bindAttribute(0, "position");
        }
    }

    private final RawModel quad = Loader.loadToVAO(new float[]{-1, 1, -1, -1, 1, 1, 1, -1}, 2);
    private final QuadShader brightPass = new QuadShader("postProcessing/brightPass.glsl");
    private final QuadShader blur = new QuadShader("postProcessing/blur.glsl");
    private final QuadShader present = new QuadShader("postProcessing/present.glsl");
    private final Fbo halfA, halfB, quarterA, quarterB;

    public PostProcessing(int width, int height) {
        halfA = new Fbo(width / 2, height / 2, 0);
        halfB = new Fbo(width / 2, height / 2, 0);
        quarterA = new Fbo(width / 4, height / 4, 0);
        quarterB = new Fbo(width / 4, height / 4, 0);

        present.start();
        present.set("scene", 0);
        present.set("bloomNear", 1);
        present.set("bloomWide", 2);
        present.stop();
    }

    /** Draws the scene texture to the window, or into {@code target} when one is given (e.g. for screenshots). */
    public void present(int sceneTexture, Fbo target) {
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL); // wireframe mode must not apply to the quad
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL30.glBindVertexArray(quad.vaoID());
        GL20.glEnableVertexAttribArray(0);

        if (GraphicsSettings.postEffects) {
            // bright areas -> half resolution, blurred horizontally then vertically
            brightPass.start();
            brightPass.set("threshold", BLOOM_THRESHOLD);
            draw(sceneTexture, halfA);
            blur.start();
            blur.set("texelStep", 1f / halfA.getWidth(), 0);
            draw(halfA.getColourTexture(), halfB);
            blur.set("texelStep", 0, 1f / halfA.getHeight());
            draw(halfB.getColourTexture(), halfA);
            // again at quarter resolution for the wide glow
            blur.set("texelStep", 1f / halfA.getWidth(), 0);
            draw(halfA.getColourTexture(), quarterA);
            blur.set("texelStep", 0, 1f / quarterA.getHeight());
            draw(quarterA.getColourTexture(), quarterB);
            blur.set("texelStep", 1f / quarterA.getWidth(), 0);
            draw(quarterB.getColourTexture(), quarterA);
            blur.set("texelStep", 0, 1f / quarterA.getHeight());
            draw(quarterA.getColourTexture(), quarterB);
        }

        if (target != null) target.bind();
        else Fbo.unbind();
        present.start();
        present.set("linearLighting", GraphicsSettings.linearLighting);
        present.set("postEffects", GraphicsSettings.postEffects);
        present.set("exposure", EXPOSURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, halfA.getColourTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, quarterB.getColourTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTexture);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, quad.vertexCount());
        present.stop();

        GL20.glDisableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        Fbo.unbind();
    }

    /** Runs the currently started quad shader with one input texture into a buffer. */
    private void draw(int sourceTexture, Fbo target) {
        target.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, quad.vertexCount());
    }

    public void cleanUp() {
        brightPass.cleanUp();
        blur.cleanUp();
        present.cleanUp();
        halfA.cleanUp();
        halfB.cleanUp();
        quarterA.cleanUp();
        quarterB.cleanUp();
    }
}
