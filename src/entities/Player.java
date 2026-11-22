package entities;

import models.TexturedModel;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector3f;
import particles.ParticleSystem;
import particles.ParticleTexture;
import renderEngine.DisplayManager;
import renderEngine.Loader;
import terrains.Terrain;
import toolbox.GameConstants;

public class Player extends Entity{

    private final static float RUN_SPEED = 0;
    private final static float TURN_SPEED = 160;
    private final static float JUMP_POWER = 20;
    private float currentSpeed = 0;
    private float currentTurnSpeed = 0;
    private float upwardsSpeed = 0;
    private boolean isJumping = false;

   transient private final ParticleSystem exhaustParticles;

    public Player(TexturedModel model, Vector3f position, float rotX, float rotY, float rotZ, float scale, String name) {
        super(model, position, rotX, rotY, rotZ, scale, name);

        // exhaust
        ParticleTexture particleTexture = new ParticleTexture(Loader.loadTexture("particles/smoke.png"), 8);
        exhaustParticles = new ParticleSystem(particleTexture, 30, 25, 1, 20);
        exhaustParticles.setDirection(new Vector3f(0, 1, 0), 0.1f);

    }

    public void move(Terrain terrain) {
        checkInputs();

        float delta = DisplayManager.getFrameTimeSeconds();
        increaseRotation(0, currentTurnSpeed * delta, 0);
        float distance = currentSpeed * delta;
        float dx = (float) (distance * Math.sin(Math.toRadians(getRotY())));
        float dz = (float) (distance * Math.cos(Math.toRadians(getRotY())));

        increasePosition(dx, 0, dz);
        upwardsSpeed += GameConstants.GRAVITY * delta;
        increasePosition(0, upwardsSpeed * delta, 0);

        float terrainHeight = terrain.getHeightOfTerrain(getPosition().x, getPosition().z);
        if (getPosition().y < terrainHeight) {
            upwardsSpeed = 0;
            getPosition().y = terrainHeight;
            isJumping = false;
        }

        if (getPosition().y > terrainHeight && !isJumping) {
            getPosition().y = terrainHeight;
        }
    }

    private void jump() {
        if (!isJumping) {
            upwardsSpeed = JUMP_POWER;
            isJumping = true;
        }
    }

    public void checkInputs() {

        if (Keyboard.isKeyDown(Keyboard.KEY_W)) {
            currentSpeed = RUN_SPEED;
            exhaustParticles.generateParticles(getPosition());
        } else
        if (Keyboard.isKeyDown(Keyboard.KEY_S)) {
            currentSpeed = -RUN_SPEED;
            exhaustParticles.generateParticles(getPosition());
        } else currentSpeed = 0;

        if (Keyboard.isKeyDown(Keyboard.KEY_A)) {
            currentTurnSpeed = TURN_SPEED;
        } else
        if (Keyboard.isKeyDown(Keyboard.KEY_D)) {
            currentTurnSpeed = -TURN_SPEED;
        } else currentTurnSpeed = 0;

        if (Keyboard.isKeyDown(Keyboard.KEY_SPACE)) {
            jump();
        }
    }
}
