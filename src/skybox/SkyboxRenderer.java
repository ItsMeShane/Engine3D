package skybox;

import entities.Camera;
import models.RawModel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Matrix4f;
import renderEngine.Environment;
import renderEngine.GraphicsSettings;
import renderEngine.Loader;

import java.io.Serializable;

public class SkyboxRenderer implements Serializable {

    private static final float SIZE = 500f;

    private static final float[] VERTICES = {
            -SIZE,  SIZE, -SIZE,
            -SIZE, -SIZE, -SIZE,
            SIZE, -SIZE, -SIZE,
            SIZE, -SIZE, -SIZE,
            SIZE,  SIZE, -SIZE,
            -SIZE,  SIZE, -SIZE,

            -SIZE, -SIZE,  SIZE,
            -SIZE, -SIZE, -SIZE,
            -SIZE,  SIZE, -SIZE,
            -SIZE,  SIZE, -SIZE,
            -SIZE,  SIZE,  SIZE,
            -SIZE, -SIZE,  SIZE,

            SIZE, -SIZE, -SIZE,
            SIZE, -SIZE,  SIZE,
            SIZE,  SIZE,  SIZE,
            SIZE,  SIZE,  SIZE,
            SIZE,  SIZE, -SIZE,
            SIZE, -SIZE, -SIZE,

            -SIZE, -SIZE,  SIZE,
            -SIZE,  SIZE,  SIZE,
            SIZE,  SIZE,  SIZE,
            SIZE,  SIZE,  SIZE,
            SIZE, -SIZE,  SIZE,
            -SIZE, -SIZE,  SIZE,

            -SIZE,  SIZE, -SIZE,
            SIZE,  SIZE, -SIZE,
            SIZE,  SIZE,  SIZE,
            SIZE,  SIZE,  SIZE,
            -SIZE,  SIZE,  SIZE,
            -SIZE,  SIZE, -SIZE,

            -SIZE, -SIZE, -SIZE,
            -SIZE, -SIZE,  SIZE,
            SIZE, -SIZE, -SIZE,
            SIZE, -SIZE, -SIZE,
            -SIZE, -SIZE,  SIZE,
            SIZE, -SIZE,  SIZE
    };

    private static final String[] DAY_TEXTURE_FILES = {
            "res/skybox/day/front.png",
            "res/skybox/day/back.png",
            "res/skybox/day/up.png",
            "res/skybox/day/down.png",
            "res/skybox/day/left.png",
            "res/skybox/day/right.png"
    };

    private static final String[] NIGHT_TEXTURE_FILES = {
            "res/skybox/night/front.png",
            "res/skybox/night/back.png",
            "res/skybox/night/up.png",
            "res/skybox/night/down.png",
            "res/skybox/night/left.png",
            "res/skybox/night/right.png"
    };

    private final RawModel cube;
    private final int DAY_TEXTURE;
    private final int NIGHT_TEXTURE;
    private final SkyboxShader shader;

    public SkyboxRenderer(Matrix4f projectionMatrix) {
        cube = Loader.loadToVAO(VERTICES, 3);
        // cube faces in the order +x, -x, +y, -y, +z, -z
        String folder = "res/skybox/" + Environment.skybox + "/";
        String[] files = {folder + "front.png", folder + "back.png", folder + "up.png", folder + "down.png", folder + "left.png", folder + "right.png"};
        DAY_TEXTURE = Loader.loadCubeMap(files);
        NIGHT_TEXTURE = DAY_TEXTURE;
        shader = new SkyboxShader();
        shader.start();
        shader.connectTextures();
        shader.loadProjectionMatrix(projectionMatrix);
        shader.stop();
    }

    public void render(Camera camera, float r, float g, float b) {
        shader.start();
        shader.loadViewMatrix(camera);
        shader.loadFogColor(r, g, b);
        shader.set("linearLighting", GraphicsSettings.linearLighting);
        shader.set("upperLimit", Environment.skyFadeHeight);

        GL30.glBindVertexArray(cube.vaoID());
        GL20.glEnableVertexAttribArray(0);
        bindTextures();
        GL11.glDepthFunc(GL11.GL_LEQUAL); // the sky sits at depth 1.0, the value the depth buffer is cleared to
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, cube.vertexCount());
        GL11.glDepthFunc(GL11.GL_LESS);
        GL20.glDisableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
        shader.stop();
    }

    private void bindTextures() {

        // add day/night cycle

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, NIGHT_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, DAY_TEXTURE);
        shader.loadBlendFactor(0.0f);
    }

}
