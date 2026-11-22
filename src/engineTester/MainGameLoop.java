package engineTester;

import editor.Editor;
import entities.Camera;
import entities.Entity;
import entities.Light;
import models.RawModel;
import models.TexturedModel;
import objConverter.ModelData;
import objConverter.OBJFileLoader;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector4f;
import particles.ParticleMaster;
import particles.ParticleSystem;
import particles.ParticleTexture;
import renderEngine.DisplayManager;
import renderEngine.Environment;
import renderEngine.FramePipeline;
import renderEngine.GraphicsSettings;
import renderEngine.Loader;
import renderEngine.MasterRenderer;
import terrains.HeightSource;
import terrains.Terrain;
import textures.ModelTexture;
import textures.TerrainTexture;
import textures.TerrainTexturePack;
import toolbox.MousePicker;
import toolbox.SceneSaver;
import toolbox.definitions.EntityDefinition;
import toolbox.definitions.LightDefinition;
import toolbox.definitions.Scene;
import water.WaterFrameBuffers;
import water.WaterRenderer;
import water.WaterShader;
import water.WaterTile;

import javax.swing.*;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainGameLoop {

	public static void main(String[] args) throws IOException {
		// --record / --preview: render the scene's b-roll shots to files instead of running interactively
		BRollRecorder recorder = BRollRecorder.fromArgs(args);
		if (recorder != null) DisplayManager.setRenderSize(recorder.width(), recorder.height());

		DisplayManager.createDisplay();

		List<Entity> entities = new ArrayList<>();
		List<Light> lights = new ArrayList<>();
		Camera camera = new Camera();


		// util
		GameScene gameScene = Scenes.select(args); // program argument, -Dscene=<name> or Scenes.DEFAULT
		Environment.reset();
		gameScene.configureEnvironment(); // sky, fog, ambient light: must be set before the renderer loads the skybox
		WaterTile.setLevel(gameScene.waterLevel());
		MasterRenderer renderer = new MasterRenderer();
		ParticleMaster.init(renderer.getProjectionMatrix());
		Terrain[][] terrains = generateTerrains(3, 3, gameScene);
		MousePicker mouse = new MousePicker(camera, renderer.getProjectionMatrix(), terrains);
		ParticleSystem particleSystem = new ParticleSystem(new ParticleTexture(Loader.loadTexture("particles/particleAtlas.png"), 4, 2f), 15, 75, 3, 20);
		particleSystem.setDirection(new Vector3f(0, 1, 0), 0.1f);


		// the selected scene adds its entities, lights, water and ambient particle effects
		List<WaterTile> waterTiles = new ArrayList<>();
		gameScene.populate(terrains, entities, lights, waterTiles);
		gameScene.setUpCamera(camera);


		// shadow, water, main and post-processing passes (toggle features with the number keys, see GraphicsSettings)
		FramePipeline pipeline = new FramePipeline(renderer);

		// scripted camera moves from broll/<scene>.txt: B plays them, N skips to the next, K prints the current view as a keyframe
		HeightSource ground = (x, z) -> groundHeight(terrains, x, z);
		BRoll bRoll = new BRoll(Scenes.selectedName(), camera, ground, gameScene);
		if (recorder != null) {
			recorder.run(Scenes.selectedName(), bRoll, gameScene, pipeline, entities, terrains, lights, camera, waterTiles);
			ParticleMaster.cleanUp();
			pipeline.cleanUp();
			renderer.cleanUp();
			Loader.cleanUp();
			DisplayManager.closeDisplay();
			System.exit(0);
		}


		// editor (must come after the scene is populated: it builds one panel per entity/light)
		Editor editor = new Editor(entities, lights, camera);
		editor.setVisible(true);


		Entity selectedEntity = null;

		while (!Display.isCloseRequested()) {
			if (!bRoll.update(editor)) { // while a b-roll shot plays, it moves the camera
				camera.move(getTerrain(terrains, camera.getFocusPoint().getPosition().x, camera.getFocusPoint().getPosition().z), ground);
			}

			GraphicsSettings.handleKeys();
			gameScene.update();
			ParticleMaster.update();
			mouse.update();

//			lights.sort(Comparator.comparingDouble(light -> getDistanceToPlayer(light, player)));


			Vector3f collisionPoint = mouse.getCurrentTerrainPoint();


			if (Mouse.isButtonDown(1)) {

				if (collisionPoint != null) {
					if (selectedEntity == null) {
						for (Entity entity : entities) {
							if (hitEntity(entity, collisionPoint)) {
								selectedEntity = entity;
							}
						}

						particleSystem.generateParticles(collisionPoint);

					}
					if (selectedEntity != null) {
						selectedEntity.setPosition(collisionPoint);
//						selectedEntity.moveEntity(collisionPoint);

						gameScene.entityDragged(selectedEntity, collisionPoint, lights);


					}
				}

			} else {
				selectedEntity = null;
			}

			pipeline.render(entities, terrains, lights, camera, waterTiles, gameScene.getSun());


			DisplayManager.updateDisplay();

			// wire mode
			GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) ? GL11.GL_LINE : GL11.GL_FILL); // wire mode
		}


//		Scene sceneCurrent = new Scene(entities, lights, camera);
		Scene sceneCurrent = new Scene(generateEntityDefinition(entities), generateLightDefinition(lights), camera);
		if (false)
			SceneSaver.saveScene(sceneCurrent);


		ParticleMaster.cleanUp();
		pipeline.cleanUp();
		renderer.cleanUp();
		Loader.cleanUp();
		DisplayManager.closeDisplay();
		editor.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		editor.dispatchEvent(new WindowEvent(editor, WindowEvent.WINDOW_CLOSING));

	}

	private static boolean hitEntity(Entity entity, Vector3f vector3f) {
		float dx = entity.getPosition().x - vector3f.x;
		float dy = entity.getPosition().y - vector3f.y;
		float dz = entity.getPosition().z - vector3f.z;
		float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		return distance <= 5.0f;
	}

	private static Terrain getTerrain(Terrain[][] terrains, float xPos, float zPos) {
		int gridX = (int) (xPos / Terrain.SIZE);
		int gridZ = (int) (zPos / Terrain.SIZE);

		gridX = Math.min(gridX, 2);
		gridZ = Math.min(gridZ, 2);

		return terrains[gridX][gridZ];
	}

	/** Ground height at any world position; far below everything outside the terrain tiles. */
	static float groundHeight(Terrain[][] terrains, float xPos, float zPos) {
		int gridX = (int) Math.floor(xPos / Terrain.SIZE), gridZ = (int) Math.floor(zPos / Terrain.SIZE);
		if (gridX < 0 || gridZ < 0 || gridX >= terrains.length || gridZ >= terrains[gridX].length) return -1000;
		return terrains[gridX][gridZ].getHeightOfTerrain(xPos, zPos);
	}

	static float getTerrainHeight(Terrain[][] terrains, float xPos, float zPos) {
		return getTerrain(terrains, xPos, zPos).getHeightOfTerrain(xPos, zPos);
	}


	private static Entity generateEntity(EntityDefinition def) {
		ModelData modelData = OBJFileLoader.loadOBJ(def.objPath());
		RawModel rawModel = Loader.loadToVAO(modelData.vertices(), modelData.textureCoords(), modelData.normals(), modelData.indices());
		TexturedModel texturedModel = new TexturedModel(rawModel, new ModelTexture(Loader.loadTexture(def.texturePath())));
		ModelTexture modelTexture = texturedModel.texture();
		modelTexture.setShineDamper(def.shineDamper());
		modelTexture.setUseFakeLighting(def.useFakeLighting());
		modelTexture.setReflectivity(def.reflectivity());
		Entity entity = new Entity(texturedModel, def.position(), def.rotX(), def.rotY(), def.rotZ(), def.scale(), def.name());
		entity.setObjPath(def.objPath());
		entity.setTexturePath(def.texturePath(), false);
		return entity;
	}

	private static Light generateLight(LightDefinition def) {
		Light light = new Light(def.position(), def.color(), def.attenuation());
		light.setName(def.name());
		return light;
	}


	private static List<EntityDefinition> generateEntityDefinition(List<Entity> entities) {
		List<EntityDefinition> definitions = new ArrayList<>();

		for (Entity e : entities) {
			EntityDefinition def = new EntityDefinition(
					e.getObjPath(),
					e.getTexturePath(),
					e.getPosition(),
					e.getRotX(),
					e.getRotY(),
					e.getRotZ(),
					e.getScale(),
					e.getModel().texture().getReflectivity(),
					e.getModel().texture().getShineDamper(),
					e.getModel().texture().isUseFakeLighting(),
					e.getName());

			definitions.add(def);
		}

		return definitions;
	}


	private static List<LightDefinition> generateLightDefinition(List<Light> lights) {
		List<LightDefinition> definitions = new ArrayList<>();

		for (Light l : lights) {
			LightDefinition def = new LightDefinition(
					l.getPosition(),
					l.getColor(),
					l.getAttenuation(),
					l.getName());

			definitions.add(def);
		}

		return definitions;
	}


	private static Entity generateEntity(String objPath, String texturePath, float xPos, float yPos, float zPos, int scale, float reflectivity, String name) {
		ModelData modelData = OBJFileLoader.loadOBJ(objPath);
		RawModel rawModel = Loader.loadToVAO(modelData.vertices(), modelData.textureCoords(), modelData.normals(), modelData.indices());
		TexturedModel texturedModel = new TexturedModel(rawModel, new ModelTexture(Loader.loadTexture(texturePath)));
		ModelTexture modelTexture = texturedModel.texture();
		modelTexture.setShineDamper(10);
//		modelTexture.setUseFakeLighting(true);/////////////////////////
		modelTexture.setReflectivity(reflectivity);
		Entity entity = new Entity(texturedModel, new Vector3f(xPos, yPos, zPos), 0, 0, 0, scale, name);
		entity.setObjPath(objPath);
		entity.setTexturePath(texturePath, false);
		return entity;
	}

	static Terrain[][] generateTerrains(int xWidth, int zWidth, GameScene gameScene) {

		// blend-mapped multi-texturing: black = background, red/green/blue channels of the blend map pick the others;
		// the textures and the per-tile blend maps belong to the scene
		TerrainTexturePack texturePack = gameScene.terrainTextures();
		Terrain[][] terrains = new Terrain[xWidth][zWidth];
		for (int x = 0; x < xWidth; x++) {
			for (int z = 0; z < zWidth; z++) {
				terrains[x][z] = new Terrain(z, x, texturePack, gameScene.blendMap(x, z), gameScene.terrainHeights()); // Terrain takes (gridZ, gridX)
			}
		}

		return terrains;
	}


	private static double getDistanceToPlayer(Light light, Entity player) {
		double dx = light.getPosition().x - player.getPosition().x;
		double dz = light.getPosition().z - player.getPosition().z;
		return dx * dx + dz * dz; // sqrt for precision
	}

}





