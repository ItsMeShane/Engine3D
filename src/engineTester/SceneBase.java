package engineTester;

import entities.Entity;
import models.RawModel;
import models.TexturedModel;
import objConverter.ModelData;
import objConverter.OBJFileLoader;
import org.lwjgl.util.vector.Vector3f;
import particles.ParticleSystem;
import particles.ParticleTexture;
import renderEngine.Loader;
import terrains.Terrain;
import textures.ModelTexture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What every scene needs to place things: cached model/texture loading, entities standing on the terrain,
 * random scattering with spacing, particle emitters, unique names for the Editor.
 */
public abstract class SceneBase implements GameScene {

    /**
     * Surface properties of a model. Part of the model cache key, so entities with the same mesh, texture and
     * look share one TexturedModel (and are drawn as one batch).
     *
     * @param transparent  alpha cut-out texture; drawn without back-face culling
     * @param windBend     how far the wind bends the model, growing with height above its base (0 = rigid)
     * @param windFlutter  small fast leaf motion, growing with distance from the stem
     * @param twoSided     light back faces as if their normal pointed the other way (real leaf geometry)
     * @param translucency share of the light that shines through from behind (leaves glow when backlit)
     * @param castsShadow  false for fine ground cover that should only receive shadows
     */
    protected record Look(float reflectivity, float shineDamper, boolean fakeLighting, boolean transparent, int atlasRows,
                          float emissive, String normalMap, float windBend, float windFlutter, boolean twoSided, float translucency, boolean castsShadow) {

        public Look(float reflectivity, float shineDamper, boolean fakeLighting, boolean transparent, int atlasRows) {
            this(reflectivity, shineDamper, fakeLighting, transparent, atlasRows, 0, null);
        }

        public Look(float reflectivity, float shineDamper, boolean fakeLighting, boolean transparent, int atlasRows, float emissive, String normalMap) {
            this(reflectivity, shineDamper, fakeLighting, transparent, atlasRows, emissive, normalMap, 0, 0, false, 0, true);
        }

        public static final Look MATTE = new Look(0, 1, false, false, 1);
        public static final Look SATIN = new Look(0.25f, 8, false, false, 1);
        public static final Look GLOSSY = new Look(0.5f, 14, false, false, 1);
        public static final Look FOLIAGE = new Look(0, 1, true, false, 1);                 // fake lighting: evenly lit leaves
        public static final Look CUTOUT_ATLAS = new Look(0, 1, true, true, 2);             // 2x2 atlas, alpha cut-out, no culling

        public Look wind(float bend, float flutter) {
            return new Look(reflectivity, shineDamper, fakeLighting, transparent, atlasRows, emissive, normalMap, bend, flutter, twoSided, translucency, castsShadow);
        }

        public Look leaves(boolean twoSided, float translucency) {
            return new Look(reflectivity, shineDamper, fakeLighting, transparent, atlasRows, emissive, normalMap, windBend, windFlutter, twoSided, translucency, castsShadow);
        }

        public Look normalMap(String normalMap) {
            return new Look(reflectivity, shineDamper, fakeLighting, transparent, atlasRows, emissive, normalMap, windBend, windFlutter, twoSided, translucency, castsShadow);
        }

        public Look cutout() {
            return new Look(reflectivity, shineDamper, fakeLighting, true, atlasRows, emissive, normalMap, windBend, windFlutter, twoSided, translucency, castsShadow);
        }

        /** For grass and other fine ground cover: it receives shadows but throws none (its own blades would only darken it). */
        public Look noShadow() {
            return new Look(reflectivity, shineDamper, fakeLighting, transparent, atlasRows, emissive, normalMap, windBend, windFlutter, twoSided, translucency, false);
        }
    }

    /** A particle system bound to the spot (or area) it emits from. */
    protected record Emitter(ParticleSystem system, Vector3f origin, float spreadX, float spreadY, float spreadZ) {
    }

    protected interface Placer {
        /** @return false to reject the spot (the scatter then tries another one) */
        boolean place(float x, float z);
    }

    protected Terrain[][] terrains;
    protected List<Entity> entities;
    protected final Random random;
    protected final List<Emitter> emitters = new ArrayList<>();

    private final Map<String, RawModel> meshCache = new HashMap<>();
    private final Map<String, ModelData> meshDataCache = new HashMap<>();
    private final Map<String, Integer> textureCache = new HashMap<>();
    private final Map<String, TexturedModel> modelCache = new HashMap<>();
    private final Map<String, Integer> nameCounters = new HashMap<>();
    private final Vector3f emitPoint = new Vector3f();

    protected SceneBase(long seed) {
        random = new Random(seed);
    }

    /** Call first in populate. */
    protected void begin(Terrain[][] terrains, List<Entity> entities) {
        this.terrains = terrains;
        this.entities = entities;
    }

    // ------------------------------------------------------------------------------------------------------------
    // placement

    /** Tries random spots in the rectangle until {@code count} were accepted, keeping {@code spacing} to all entities. */
    protected void scatter(float x0, float x1, float z0, float z1, int count, float spacing, Placer placer) {
        int placed = 0;
        for (int attempt = 0; attempt < count * 60 && placed < count; attempt++) {
            float x = x0 + random.nextFloat() * (x1 - x0), z = z0 + random.nextFloat() * (z1 - z0);
            if (tooClose(x, z, spacing)) continue;
            if (placer.place(x, z)) placed++;
        }
    }

    protected boolean tooClose(float x, float z, float minDistance) {
        for (Entity e : entities) {
            float dx = e.getPosition().x - x, dz = e.getPosition().z - z;
            if (dx * dx + dz * dz < minDistance * minDistance) return true;
        }
        return false;
    }

    /** "name 1", "name 2", ... : the Editor shows one card per entity name, so names must be unique. */
    protected String next(String name) {
        return name + " " + nameCounters.merge(name, 1, Integer::sum);
    }

    protected float height(float x, float z) {
        return MainGameLoop.getTerrainHeight(terrains, x, z);
    }

    /**
     * The flat plane that best matches the ground around a spot.
     *
     * @param height     of the plane at the spot itself
     * @param slopeX     rise per unit toward +x
     * @param slopeZ     rise per unit toward +z
     * @param unevenness the furthest the real ground strays from the plane within the radius (bumps, a bank's edge)
     */
    protected record GroundFit(float height, float slopeX, float slopeZ, float unevenness) {
    }

    protected GroundFit fitGround(float x, float z, float radius) {
        final float[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0.7f, 0.7f}, {0.7f, -0.7f}, {-0.7f, 0.7f}, {-0.7f, -0.7f}};
        float[] heights = new float[offsets.length];
        float mean = 0, sumXH = 0, sumZH = 0, sumXX = 0, sumZZ = 0;
        for (int i = 0; i < offsets.length; i++) {
            float dx = offsets[i][0] * radius, dz = offsets[i][1] * radius;
            heights[i] = height(x + dx, z + dz);
            mean += heights[i] / offsets.length;
            sumXH += dx * heights[i];
            sumZH += dz * heights[i];
            sumXX += dx * dx;
            sumZZ += dz * dz;
        }
        // the sample points are symmetric around the centre, so the least-squares plane separates into these two slopes
        float slopeX = sumXH / sumXX, slopeZ = sumZH / sumZZ, unevenness = 0;
        for (int i = 0; i < offsets.length; i++)
            unevenness = Math.max(unevenness, Math.abs(heights[i] - (mean + slopeX * offsets[i][0] * radius + slopeZ * offsets[i][1] * radius)));
        return new GroundFit(mean, slopeX, slopeZ, unevenness);
    }

    /**
     * Places a wide, flat model (a patch of grass, a carpet of leaves) lying on the fitted plane instead of level,
     * so its edges neither float nor sink on a slope. The engine turns a model around Y before X, which rules out
     * a free heading here: the model is either as built or turned half way round ({@code flip}).
     */
    protected Entity onSlope(String name, String model, String texture, GroundFit ground, float x, float z, float yOffset, boolean flip, float scale, Look look) {
        float length = (float) Math.sqrt(ground.slopeX() * ground.slopeX() + 1 + ground.slopeZ() * ground.slopeZ());
        float normalX = -ground.slopeX() / length, normalY = 1 / length, normalZ = -ground.slopeZ() / length;
        Entity entity = at(name, model, texture, x, ground.height() + yOffset, z, flip ? 180 : 0, scale, look);
        entity.setRotZ(degrees((flip ? 1 : -1) * Math.asin(normalX)));
        entity.setRotX(degrees(Math.atan2(normalZ, normalY)));
        return entity;
    }

    /** Radians to degrees in 0..360 (the Editor's rotation sliders cover that range). */
    protected static float degrees(double radians) {
        return (float) ((Math.toDegrees(radians) % 360 + 360) % 360);
    }

    protected static Vector3f above(Entity entity, float dy) {
        return new Vector3f(entity.getPosition().x, entity.getPosition().y + dy, entity.getPosition().z);
    }

    // ------------------------------------------------------------------------------------------------------------
    // particles

    /**
     * Particle system using a square atlas of {@code rows} x {@code rows} frames.
     *
     * @param glow    brightness multiplier with linear lighting; above 1 the particles bloom
     * @param gravity 1 falls, 0 floats, negative rises
     */
    protected ParticleSystem particles(String texturePath, int rows, float glow, float perSecond, float speed, float life, float scale, float gravity) {
        ParticleSystem system = new ParticleSystem(new ParticleTexture(texture(texturePath), rows, glow), perSecond, speed, life, scale);
        system.setGravityEffect(gravity);
        system.setSpeedError(0.3f);
        system.setLifeError(0.25f);
        system.setScaleError(0.35f);
        system.randomizeRotation();
        return system;
    }

    /** Call every frame: each emitter spawns its particles at a random point of its area. */
    protected void runEmitters() {
        for (Emitter emitter : emitters) {
            emitPoint.set(emitter.origin.x + (random.nextFloat() - 0.5f) * 2 * emitter.spreadX,
                    emitter.origin.y + (random.nextFloat() - 0.5f) * 2 * emitter.spreadY,
                    emitter.origin.z + (random.nextFloat() - 0.5f) * 2 * emitter.spreadZ);
            emitter.system.generateParticles(emitPoint);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // entity creation

    /** Loads a texture once; every model and particle system that names it shares the same GPU texture. */
    protected int texture(String path) {
        return textureCache.computeIfAbsent(path, Loader::loadTexture);
    }

    /** Entity standing on the terrain (plus yOffset). model and texture are paths below res/models and res/textures, without extension. */
    protected Entity onGround(String name, String model, String texture, float x, float z, float yOffset, float rotY, float scale, Look look) {
        return at(name, model, texture, x, height(x, z) + yOffset, z, rotY, scale, look);
    }

    /** Creates an entity at an exact position. Meshes and textures are loaded once and shared. */
    protected Entity at(String name, String model, String texture, float x, float y, float z, float rotY, float scale, Look look) {
        String objPath = "models/" + model + ".obj";
        String texturePath = "textures/" + texture + ".png";

        TexturedModel texturedModel = modelCache.computeIfAbsent(objPath + "|" + texturePath + "|" + look, key -> {
            RawModel mesh = meshCache.computeIfAbsent(objPath, path -> {
                ModelData data = OBJFileLoader.loadOBJ(path);
                return Loader.loadToVAO(data.vertices(), data.textureCoords(), data.normals(), data.indices());
            });
            return new TexturedModel(mesh, material(texturePath, look));
        });

        String uniqueName = entities.stream().anyMatch(e -> e.getName().equals(name)) ? next(name) : name;
        if (uniqueName.equals(name)) nameCounters.putIfAbsent(name, 1);

        Entity entity = new Entity(texturedModel, new Vector3f(x, y, z), 0, ((rotY % 360) + 360) % 360, 0, scale, uniqueName);
        entity.setObjPath(objPath);
        entity.setTexturePath(texturePath, false);
        entities.add(entity);
        return entity;
    }

    private ModelTexture material(String texturePath, Look look) {
        ModelTexture modelTexture = new ModelTexture(texture(texturePath));
        modelTexture.setReflectivity(look.reflectivity());
        modelTexture.setShineDamper(look.shineDamper());
        modelTexture.setUseFakeLighting(look.fakeLighting());
        modelTexture.setHasTransparency(look.transparent());
        modelTexture.setNumberOfRows(look.atlasRows());
        modelTexture.setEmissive(look.emissive());
        modelTexture.setWind(look.windBend(), look.windFlutter());
        modelTexture.setTwoSidedLighting(look.twoSided());
        modelTexture.setTranslucency(look.translucency());
        modelTexture.setCastsShadow(look.castsShadow());
        if (look.normalMap() != null) modelTexture.setNormalMap(texture("textures/" + look.normalMap() + ".png"));
        return modelTexture;
    }

    /**
     * Many copies of models baked into a single mesh and drawn as one entity. The engine draws every entity with
     * its own draw call in each pass, so this is the way to have hundreds of background trees (the hills around a
     * valley) for the cost of one. The copies cannot be selected or moved one by one, and they share one texture.
     */
    protected class Batch {
        private record Copy(String model, float x, float y, float z, float rotY, float scale) {
        }

        private final List<Copy> copies = new ArrayList<>();

        public void add(String model, float x, float y, float z, float rotY, float scale) {
            copies.add(new Copy(model, x, y, z, rotY, scale));
        }

        public int size() {
            return copies.size();
        }

        /** Creates the entity (null if nothing was added). Its position is the middle of the copies. */
        public Entity build(String name, String texture, Look look) {
            if (copies.isEmpty()) return null;
            float centreX = 0, centreY = 0, centreZ = 0;
            int vertexCount = 0, indexCount = 0;
            for (Copy copy : copies) {
                centreX += copy.x / copies.size();
                centreY += copy.y / copies.size();
                centreZ += copy.z / copies.size();
                ModelData data = meshDataCache.computeIfAbsent("models/" + copy.model + ".obj", OBJFileLoader::loadOBJ);
                vertexCount += data.vertices().length / 3;
                indexCount += data.indices().length;
            }
            float[] positions = new float[vertexCount * 3], uvs = new float[vertexCount * 2], normals = new float[vertexCount * 3];
            int[] indices = new int[indexCount];
            int vertex = 0, index = 0;
            for (Copy copy : copies) {
                ModelData data = meshDataCache.get("models/" + copy.model + ".obj");
                float cos = (float) Math.cos(Math.toRadians(copy.rotY)), sin = (float) Math.sin(Math.toRadians(copy.rotY));
                int count = data.vertices().length / 3;
                for (int i = 0; i < count; i++) {
                    float x = data.vertices()[i * 3], y = data.vertices()[i * 3 + 1], z = data.vertices()[i * 3 + 2];
                    float nx = data.normals()[i * 3], nz = data.normals()[i * 3 + 2];
                    int v = vertex + i;
                    positions[v * 3] = copy.x - centreX + (x * cos + z * sin) * copy.scale; // same turn as the engine's rotY
                    positions[v * 3 + 1] = copy.y - centreY + y * copy.scale;
                    positions[v * 3 + 2] = copy.z - centreZ + (-x * sin + z * cos) * copy.scale;
                    normals[v * 3] = nx * cos + nz * sin;
                    normals[v * 3 + 1] = data.normals()[i * 3 + 1];
                    normals[v * 3 + 2] = -nx * sin + nz * cos;
                    uvs[v * 2] = data.textureCoords()[i * 2];
                    uvs[v * 2 + 1] = data.textureCoords()[i * 2 + 1];
                }
                for (int i : data.indices()) indices[index++] = vertex + i;
                vertex += count;
            }
            String texturePath = "textures/" + texture + ".png";
            TexturedModel model = new TexturedModel(Loader.loadToVAO(positions, uvs, normals, indices), material(texturePath, look));
            Entity entity = new Entity(model, new Vector3f(centreX, centreY, centreZ), 0, 0, 0, 1, next(name));
            entity.setObjPath("models/" + copies.get(0).model + ".obj");
            entity.setTexturePath(texturePath, false);
            entities.add(entity);
            return entity;
        }
    }
}
