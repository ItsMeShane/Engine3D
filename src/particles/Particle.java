package particles;


import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import renderEngine.DisplayManager;
import toolbox.GameConstants;

public class Particle {

    private final Vector3f position;
    private final Vector3f velocity;
    private final float gravityEffect; // 1 = normal gravity, 0 = floats, negative = rises
    private final float lifeLength;
    private final float rotation;
    private final float scale;
    private float elapsedTime = 0;

    private final ParticleTexture texture;
    private final Vector2f textureOffset1 = new Vector2f();
    private final Vector2f textureOffset2 = new Vector2f();
    private float blend;

    public Particle(ParticleTexture texture, Vector3f position, Vector3f velocity, float lifeLength, float rotation, float scale) {
        this(texture, position, velocity, 1, lifeLength, rotation, scale);
    }

    public Particle(ParticleTexture texture, Vector3f position, Vector3f velocity, float gravityEffect, float lifeLength, float rotation, float scale) {
        this.gravityEffect = gravityEffect;
        this.texture = texture;
        this.position = position;
        this.velocity = velocity;
        this.lifeLength = lifeLength;
        this.rotation = rotation;
        this.scale = scale;

        ParticleMaster.addParticle(this);
    }

    private final Vector3f reusableParticle = new Vector3f();
    protected boolean update() {
        float delta = DisplayManager.getFrameTimeSeconds();
        velocity.y += GameConstants.GRAVITY * gravityEffect * delta;
        reusableParticle.set(velocity);
        reusableParticle.scale(delta);
        Vector3f.add(reusableParticle, position, position);
        elapsedTime += delta;
        updateTextureCoords();
        return elapsedTime < lifeLength;
    }

    private void updateTextureCoords() {
        float lifeFactor = elapsedTime / lifeLength;
        int stageCount = texture.numberOfRows() * texture.numberOfRows();
        float atlasProgression = lifeFactor * stageCount;

        int index1 = (int) Math.floor(atlasProgression);
        int index2 = index1 < stageCount - 1 ? index1 + 1 : index1;
        blend = atlasProgression % 1;
        setTextureOffset(textureOffset1, index1);
        setTextureOffset(textureOffset2, index2);
    }

    private void setTextureOffset(Vector2f offset, int index) {
        int col = index % texture.numberOfRows();
        int row = index / texture.numberOfRows();
        offset.x = (float) col / texture.numberOfRows();
        offset.y = (float) row / texture.numberOfRows();
    }

    protected Vector3f getPosition() {
        return position;
    }

    protected float getRotation() {
        return rotation;
    }

    protected float getScale() {
        return scale;
    }

    protected ParticleTexture getTexture() {
        return texture;
    }

    public Vector2f getTextureOffset1() {
        return textureOffset1;
    }

    public Vector2f getTextureOffset2() {
        return textureOffset2;
    }

    public float getBlend() {
        return blend;
    }
}
