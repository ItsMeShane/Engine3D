package water;

import entities.Camera;
import entities.Light;
import models.RawModel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Matrix4f;
import renderEngine.DisplayManager;
import renderEngine.Environment;
import renderEngine.FrameProfiler;
import renderEngine.GraphicsSettings;
import renderEngine.Loader;

import java.util.List;

public class WaterRenderer {

	private static final String DUDV_MAP = "water/waterDUDV.png";
//	private static final String DUDV_MAP = "water/test.png";
	private static final String NORMAL_MAP = "water/normal.png";
	private static final float WAVE_SPEED = 0.03f;

	private RawModel quad;
	private final WaterShader shader;
	private final WaterFrameBuffers wfb;

	private float moveFactor = 0;

	private final int dudvTexture;
	private final int normalMap;

	public WaterRenderer(WaterShader shader, Matrix4f projectionMatrix, WaterFrameBuffers wfb) {
		this.shader = shader;
		this.wfb = wfb;
		dudvTexture = Loader.loadTexture(DUDV_MAP);
		normalMap = Loader.loadTexture(NORMAL_MAP);
		shader.start();
		shader.connectTextureUnits();
		shader.loadProjectionMatrix(projectionMatrix);
		shader.stop();
		setUpVAO();
	}

	public void render(List<WaterTile> water, Camera camera, Light sun) {
		prepareRender(camera, sun);
		for (WaterTile tile : water) {
			shader.loadModelMatrix(tile.transformationMatrix());
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, quad.vertexCount());
			FrameProfiler.countDraw(quad.vertexCount() / 3);
		}
		unbind();
	}

	private void prepareRender(Camera camera, Light sun) {
		shader.start();
		shader.loadViewMatrix(camera);
		moveFactor += WAVE_SPEED * DisplayManager.getFrameTimeSeconds();
		moveFactor %= 1;
		shader.loadMoveFactor(moveFactor);
		shader.set("linearLighting", GraphicsSettings.linearLighting);
		shader.set("waterEffects", GraphicsSettings.waterEffects);
		shader.set("fogDensity", Environment.fogDensity);
		shader.set("skyColor", Environment.fogColour);
		shader.set("waterTiling", Environment.waterTiling);
		shader.set("waterGlint", Environment.waterGlint);
		shader.set("waterMurkiness", Environment.waterMurkiness);
		shader.set("waterDeepColour", Environment.waterDeepColour);
		shader.set("farPlane", Environment.farPlane);
		shader.loadLight(sun); // sun drives the specular highlights on the water
		GL30.glBindVertexArray(quad.vaoID());
		GL20.glEnableVertexAttribArray(0);
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, wfb.getReflectionTexture());
		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, wfb.getRefractionTexture());
		GL13.glActiveTexture(GL13.GL_TEXTURE2);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, dudvTexture);
		GL13.glActiveTexture(GL13.GL_TEXTURE3);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, normalMap);
		GL13.glActiveTexture(GL13.GL_TEXTURE4);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, wfb.getRefractionDepthTexture());

		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

	}

	private void unbind() {
		GL11.glDisable(GL11.GL_BLEND);
		GL20.glDisableVertexAttribArray(0);
		GL30.glBindVertexArray(0);
		shader.stop();
	}

	private void setUpVAO() {
		// Just x and z vector positions here, y is set to 0 in v.shader
		float[] vertices = {-1, -1, -1, 1, 1, -1, 1, -1, -1, 1, 1, 1};
		quad = Loader.loadToVAO(vertices, 2);
	}

}
