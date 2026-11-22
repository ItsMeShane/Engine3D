package engineTester;

import entities.Camera;
import entities.Entity;
import entities.Light;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.vector.Vector3f;
import particles.ParticleSystem;
import particles.ParticleTexture;
import renderEngine.DisplayManager;
import renderEngine.Environment;
import renderEngine.Loader;
import terrains.HeightSource;
import terrains.Terrain;
import textures.TerrainTexture;
import textures.TerrainTexturePack;
import water.WaterTile;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * "forest": a clearing by a lake in a conifer forest, late in the afternoon. Realistic counterpart of the
 * village scene, built from photo-scanned CC0 assets (see tools/BuildForestAssets and ASSET_CREDITS.md).
 *
 * <pre>
 *                 north (-z)            the sun stands low in the north-north-west,
 *        forested hills                 so it shines across the lake into the clearing
 *      ~~~~~~~~ LAKE ~~~~~~~~
 *      ~~~~~~~ (mist) ~~~~~~~
 *         reeds  |jetty + lantern
 *      meadow ---+---- CLEARING: campfire, logs, table
 *        forest all around (firs, pines, a few broadleaf trees)
 *                 south (+z)
 * </pre>
 *
 * This scene has its own terrain (heightAt below), its own sky and fog, and a smaller scale than the village:
 * 3.5 world units per metre, so the firs are 50-80 units tall.
 *
 * What moves: wind in every tree, bush, fern and grass patch (and their shadows), the flickering campfire and its
 * light, smoke and embers, fireflies along the shore, dust motes in the sunlight, falling leaves, mist drifting
 * over the lake, and the water itself.
 */
public class ForestScene extends SceneBase {

    private static final float WATER = 0;
    private static final float CENTRE_X = 1200, CENTRE_Z = 1150;
    private static final float LAKE_X = 1180, LAKE_Z = 1035, LAKE_RADIUS_X = 195, LAKE_RADIUS_Z = 128;
    private static final float CLEARING_X = 1215, CLEARING_Z = 1268, CLEARING_RADIUS = 92;
    private static final float FIRE_X = 1231, FIRE_Z = 1262;
    private static final float JETTY_X = 1193;

    /** Direction toward the sun: the sky photo's sun was turned to this compass angle by tools/BuildForestAssets (SUN_AZIMUTH). */
    private static final double SUN_AZIMUTH = Math.toRadians(-112), SUN_ELEVATION = Math.toRadians(21);

    private static final float TREE_BEND = 0.05f;
    private static final Look BARK_CONIFER = new Look(0.05f, 6, false, false, 1).normalMap("forest/bark_conifer_normal").wind(TREE_BEND, 0);
    private static final Look BARK_BROADLEAF = new Look(0.05f, 6, false, false, 1).normalMap("forest/bark_broadleaf_normal").wind(TREE_BEND, 0);
    private static final Look NEEDLES = Look.MATTE.cutout().wind(TREE_BEND, 0.16f).leaves(false, 0.3f);
    private static final Look LEAVES = Look.MATTE.cutout().wind(TREE_BEND, 0.22f).leaves(false, 0.55f);
    private static final Look GRASS = Look.MATTE.cutout().wind(1.8f, 0.07f).noShadow();
    private static final Look FERN = Look.MATTE.cutout().wind(2.4f, 0.04f).leaves(true, 0.45f).noShadow();

    private Light sun, fireLight, lanternLight;
    private float jettyEndZ, shoreZ;

    public ForestScene() {
        super(19);
    }

    // ------------------------------------------------------------------------------------------------------------
    // environment + terrain

    @Override
    public void configureEnvironment() {
        Environment.skybox = "golden";
        Environment.skyRotationSpeed = 0;       // the sun in the sky photo must stay where the sun light comes from
        Environment.skyFadeHeight = 16;
        Environment.fogColour.set(0.68f, 0.66f, 0.63f); // the photo's warm haze near the horizon
        Environment.fogDensity = 0.0015f;
        Environment.ambientSky.set(0.19f, 0.25f, 0.36f);
        Environment.ambientGround.set(0.12f, 0.10f, 0.07f);
        Environment.terrainTiling = 46;         // about 5 m of ground per texture repeat
        Environment.waterTiling = 9;            // small ripples: this is a sheltered lake, not open water
        Environment.waterGlint = 1.1f;          // looking into a low sun: keep the glitter path from burning out
    }

    @Override
    public float waterLevel() {
        return WATER;
    }

    @Override
    public HeightSource terrainHeights() {
        return ForestScene::heightAt;
    }

    /** The lake basin, the nearly flat clearing on its south shore, gentle forest ground and hills that close the view. */
    private static float heightAt(float x, float z) {
        float land = 3.2f + fbm(x * 0.011f, z * 0.011f) * 7.5f;

        float distance = (float) Math.hypot(x - CENTRE_X, z - CENTRE_Z);
        float rise = smoothstep(270, 820, distance);
        land += rise * (34 + fbm(x * 0.0035f + 9, z * 0.0035f + 4) * 44) + rise * (fbm(x * 0.02f + 3, z * 0.02f) - 0.5f) * 12;

        float clearing = 1 - smoothstep(0.55f, 1.05f, (float) Math.hypot(x - CLEARING_X, z - CLEARING_Z) / CLEARING_RADIUS);
        float flat = 2.6f + (z - 1180) * 0.022f + (fbm(x * 0.03f, z * 0.03f) - 0.5f) * 1.3f; // slopes gently down to the water
        land += (flat - land) * clearing * 0.9f;

        float lake = lakeDistance(x, z);
        float basin = 1 - smoothstep(0.72f, 1.22f, lake);
        float bed = -15 + fbm(x * 0.02f, z * 0.02f) * 4;
        return land + (bed - land) * basin * basin * (3 - 2 * basin);
    }

    /** Below 1 inside the lake, 1 around the shoreline; the outline wobbles so the shore isn't an ellipse. */
    private static float lakeDistance(float x, float z) {
        float dx = (x - LAKE_X) / LAKE_RADIUS_X, dz = (z - LAKE_Z) / LAKE_RADIUS_Z;
        return (float) Math.sqrt(dx * dx + dz * dz) + (fbm(x * 0.013f + 20, z * 0.013f + 7) - 0.5f) * 0.42f;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0, Math.min(1, (value - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    /** Smooth noise in 0..1, four octaves. */
    private static float fbm(float x, float z) {
        float sum = 0, amplitude = 0.5f, total = 0;
        for (int octave = 0; octave < 4; octave++) {
            sum += noise(x, z) * amplitude;
            total += amplitude;
            x = x * 2.03f + 17.1f;
            z = z * 2.03f + 5.7f;
            amplitude *= 0.5f;
        }
        return sum / total;
    }

    private static float noise(float x, float z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        float fx = x - ix, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        float a = hash(ix, iz), b = hash(ix + 1, iz), c = hash(ix, iz + 1), d = hash(ix + 1, iz + 1);
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
    }

    private static float hash(int x, int z) {
        int h = (x * 0x27d4eb2d) ^ (z * 0x165667b1);
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        h *= 0x297a2d39;
        h ^= h >>> 15;
        return (h & 0xffff) / 65535f;
    }

    // ------------------------------------------------------------------------------------------------------------
    // ground textures

    /** Background = forest floor, red = shore gravel and lake bed, green = meadow grass, blue = bare trodden earth. */
    @Override
    public TerrainTexturePack terrainTextures() {
        return new TerrainTexturePack(ground("forest/ground_forest"), ground("forest/ground_shore"), ground("forest/ground_grass"), ground("forest/ground_dirt"));
    }

    private static TerrainTexture ground(String name) {
        return new TerrainTexture(Loader.loadTexture("textures/" + name + ".png"), Loader.loadTexture("textures/" + name + "_normal.png"));
    }

    /** Painted from the terrain itself when the scene loads, so the textures always follow the shape above. */
    @Override
    public TerrainTexture blendMap(int tileX, int tileZ) {
        int size = tileX == 1 && tileZ == 1 ? 1024 : 128; // everything of interest is on the centre tile
        ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
        for (int py = 0; py < size; py++)
            for (int px = 0; px < size; px++) {
                float x = (tileX + (px + 0.5f) / size) * Terrain.SIZE, z = (tileZ + (py + 0.5f) / size) * Terrain.SIZE;
                float h = heightAt(x, z);
                float shore = 1 - smoothstep(0.5f, 2.4f, h + (fbm(x * 0.09f, z * 0.09f) - 0.5f) * 1.2f);
                float dirt = Math.max(trodden(x, z), smoothstep(0.66f, 0.8f, fbm(x * 0.035f + 31, z * 0.035f + 13)) * 0.7f) * (1 - shore);
                float grass = meadow(x, z) * (1 - shore) * (1 - dirt);
                pixels.put((byte) (shore * 255)).put((byte) (grass * 255)).put((byte) (dirt * 255)).put((byte) 255);
            }
        pixels.flip();
        return new TerrainTexture(Loader.loadTexture(size, size, pixels));
    }

    /** 1 in the sunny meadow of the clearing, fading into the forest floor with a ragged edge. */
    private static float meadow(float x, float z) {
        float d = (float) Math.hypot(x - CLEARING_X, z - CLEARING_Z) / (CLEARING_RADIUS * 1.12f) + (fbm(x * 0.045f + 3, z * 0.045f + 8) - 0.5f) * 0.55f;
        return 1 - smoothstep(0.72f, 1.05f, d);
    }

    /** 1 where feet have worn the grass away: around the fire and along the way to the jetty. */
    private static float trodden(float x, float z) {
        float fire = (float) Math.hypot(x - FIRE_X, z - FIRE_Z) - 9;
        float dx = JETTY_X - FIRE_X, dz = 1182 - FIRE_Z, t = Math.max(0, Math.min(1, ((x - FIRE_X) * dx + (z - FIRE_Z) * dz) / (dx * dx + dz * dz)));
        float path = (float) Math.hypot(x - (FIRE_X + t * dx), z - (FIRE_Z + t * dz)) - 2.2f;
        return 1 - smoothstep(-1.5f, 3.5f, Math.min(fire, path) + (fbm(x * 0.2f, z * 0.2f) - 0.5f) * 3);
    }

    // ------------------------------------------------------------------------------------------------------------
    // contents

    @Override
    public void populate(Terrain[][] terrains, List<Entity> entities, List<Light> lights, List<WaterTile> waterTiles) {
        begin(terrains, entities);
        shoreZ = findShore(JETTY_X);

        addLights(lights);
        addCamp();
        addJetty();
        addTrees();
        addRocksAndDeadwood();
        addUndergrowth();
        addAmbientEffects();

        waterTiles.add(new WaterTile(Terrain.SIZE * 1.5f, Terrain.SIZE * 1.5f, WATER, Terrain.SIZE * 1.5f));
    }

    /** Opening shot: from the back of the clearing, over the campfire, out across the lake toward the low sun. */
    @Override
    public void setUpCamera(Camera camera) {
        camera.getFocusPoint().getPosition().set(1214, height(1214, 1236), 1236);
        camera.setAngleAroundFocusPoint(192);
        camera.setAngleAboveFocusPoint(11);
        camera.setDistanceFromFocusPoint(78);
    }

    @Override
    public void update() {
        runEmitters();

        // firelight: brightness wanders the way flames do (slow swell + quick flutter), and the light point jumps a little
        float time = DisplayManager.getTime();
        float flicker = 0.74f + 0.16f * (float) Math.sin(time * 5.3f) * (float) Math.sin(time * 1.7f + 1) + 0.1f * noise(time * 11, 3.3f);
        fireLight.getColor().set(1.0f * flicker, 0.5f * flicker, 0.16f * flicker);
        fireLight.getPosition().y = firePosition.y + 3.2f + 0.5f * noise(time * 7, 9.1f);
        fireLight.getPosition().x = firePosition.x + (noise(time * 6, 1.7f) - 0.5f) * 0.8f;
        float glow = 0.9f + 0.1f * noise(time * 4, 6.6f);
        lanternLight.getColor().set(1.0f * glow, 0.72f * glow, 0.36f * glow);
    }

    @Override
    public Light getSun() {
        return sun;
    }

    private Vector3f firePosition;

    private void addLights(List<Light> lights) {
        // far away + no attenuation = directional light, standing where the sun is in the sky photo
        Vector3f toSun = new Vector3f((float) (Math.cos(SUN_ELEVATION) * Math.cos(SUN_AZIMUTH)), (float) Math.sin(SUN_ELEVATION), (float) (Math.cos(SUN_ELEVATION) * Math.sin(SUN_AZIMUTH)));
        sun = new Light(new Vector3f(CENTRE_X + toSun.x * 6000, toSun.y * 6000, CENTRE_Z + toSun.z * 6000), new Vector3f(1.0f, 0.83f, 0.62f), new Vector3f(1, 0, 0));
        sun.setName("sun");
        lights.add(sun);

        firePosition = new Vector3f(FIRE_X, height(FIRE_X, FIRE_Z), FIRE_Z);
        fireLight = new Light(new Vector3f(FIRE_X, firePosition.y + 3.2f, FIRE_Z), new Vector3f(1, 0.5f, 0.16f), new Vector3f(0.55f, 0.03f, 0.006f));
        fireLight.setName("campfire light");
        lights.add(fireLight);

        lanternLight = new Light(new Vector3f(JETTY_X, 4, shoreZ), new Vector3f(1, 0.72f, 0.36f), new Vector3f(0.7f, 0.05f, 0.012f)); // moved onto the jetty in addJetty
        lanternLight.setName("lantern light");
        lights.add(lanternLight);
    }

    private void addCamp() {
        float fx = FIRE_X, fz = FIRE_Z;
        Entity pit = prop("campfire", "demo/real/fire_pit", fx, fz, -0.26f, 0, 0.5f); // sunk so its stones sit in the earth (y = 4.2)
        layLog("log seat", fx + 8.5f, fz + 3.5f, 62, 0.8f, 0);
        layLog("log seat", fx - 4.5f, fz + 9.5f, 160, 0.7f, 0);
        float stumpScale = 0.42f;
        Entity stump = prop("chopping stump", "demo/real/stump", fx - 10, fz - 3, -0.3f, 30, stumpScale);
        addAxe(stump.getPosition().x, stump.getPosition().y + 3.75f * stumpScale, stump.getPosition().z); // the stump model's cut face is 3.75 above its base
        prop("firewood", "forest/firewood", fx - 13.5f, fz + 2.5f, -0.1f, 15, 1);
        prop("stool", "forest/stool", fx + 2.5f, fz - 8.5f, 0, 25, 1);
        prop("cooking pot", "forest/pot", fx - 3.2f, fz - 2.4f, 0, 0, 1, new Look(0.6f, 12, false, false, 1).normalMap("forest/pot_normal"));
        Entity table = prop("picnic table", "forest/picnic_table", fx + 15, fz - 9, -0.1f, 28, 1);
        prop("bucket", "forest/bucket", fx + 9.5f, fz - 12.5f, 0, 110, 1);
        prop("barrel", "demo/real/barrel", fx + 20.5f, fz - 3.5f, 0, 0, 0.5f);
        prop("crate", "demo/real/crate", fx + 19, fz + 1.5f, 0, 75, 0.75f);

        // flames and smoke rise (negative gravity); embers shoot up, cool and fade
        ParticleSystem flame = particles("particles/demo/flame.png", 4, 2.6f, 26, 1.7f, 0.75f, 2.5f, -0.13f);
        flame.setDirection(new Vector3f(0, 1, 0), 0.2f);
        emitters.add(new Emitter(flame, above(pit, 1.1f), 0.7f, 0, 0.7f));
        ParticleSystem smoke = particles("particles/demo/smoke.png", 4, 1, 4, 2.2f, 5, 4.5f, -0.03f);
        smoke.setDirection(new Vector3f(0.45f, 1, 0.25f), 0.12f);
        emitters.add(new Emitter(smoke, above(pit, 4), 0.4f, 0, 0.4f));
        ParticleSystem embers = particles("particles/forest/ember.png", 4, 4.5f, 8, 5.5f, 1.7f, 0.42f, -0.07f);
        embers.setDirection(new Vector3f(0.15f, 1, 0.08f), 0.2f);
        emitters.add(new Emitter(embers, above(pit, 1.5f), 0.8f, 0, 0.8f));

        // a broadleaf tree toward the sun throws moving, dappled shade over the camp; its leaves drift down
        Entity shade = tree("broadleaf_a", fx - 37, fz - 27, 1.25f, 200);
        ParticleSystem leaves = particles("particles/forest/leaf.png", 4, 1, 0.9f, 1.6f, 9, 1.0f, 0.011f);
        leaves.setDirection(new Vector3f(0.8f, -0.2f, 0.45f), 0.3f);
        emitters.add(new Emitter(leaves, above(shade, 38), 16, 6, 16));
    }

    /**
     * An axe left stuck in a chopping block whose top surface is at (x, topY, z): the blade edge bites into the
     * wood and the handle rises toward the south.
     *
     * The axe model stands on the end of its handle (the model origin) with the head on top; the middle of the
     * blade edge is at model (0, 1.95, 0.37). The engine rotates around Y first and X second, so: a half turn
     * around Y puts the edge on the -z side, then tilting 235 degrees around X swings the head over and down.
     * That leaves the handle 35 degrees above the horizontal with the edge pointing down into the block.
     */
    private void addAxe(float x, float topY, float z) {
        final float tilt = 235, edgeY = 1.95f, edgeZ = -0.37f; // edge position after the half turn
        double a = Math.toRadians(tilt);
        // where the tilt moves the blade edge relative to the model origin
        float edgeDy = (float) (edgeY * Math.cos(a) - edgeZ * Math.sin(a)), edgeDz = (float) (edgeY * Math.sin(a) + edgeZ * Math.cos(a));
        float bite = 0.1f; // how deep the middle of the edge sits in the wood (the toe goes deeper, the heel stays out)
        Entity axe = at("axe", "forest/axe", "forest/axe", x, topY - bite - edgeDy, z - edgeDz, 180, 1, scanned("forest/axe"));
        axe.setRotX(tilt);
    }

    private void addJetty() {
        // the pier model is 32.8 long (along z). "deck" is the height of its cross beams; the planks you stand on are
        // 3.25 higher, so everything placed on the jetty uses deck + 3.25
        float deck = WATER + 1.5f, centreZ = shoreZ - 11.5f;
        at("jetty", "forest/pier", "forest/pier", JETTY_X, deck - 9.3f, centreZ, 0, 1, scanned("forest/pier"));
        jettyEndZ = centreZ - 16;

        Entity lantern = at("lantern", "forest/lantern", "forest/lantern", JETTY_X + 2.6f, deck + 3.25f, jettyEndZ + 2.5f, 20, 1,
                new Look(0.2f, 8, false, false, 1, 2.6f, "forest/lantern_normal")); // emissive: the panes glow
        lanternLight.setPosition(new Vector3f(lantern.getPosition().x, deck + 3.25f + 1.6f, lantern.getPosition().z));
        at("lantern", "forest/lantern", "forest/lantern", FIRE_X + 15.5f, height(FIRE_X + 15, FIRE_Z - 9) + 2.5f, FIRE_Z - 8.5f, 200, 1,
                new Look(0.2f, 8, false, false, 1, 2.6f, "forest/lantern_normal")); // a second one on the picnic table
        at("bucket", "forest/bucket", "forest/bucket", JETTY_X - 2.4f, deck + 3.25f, jettyEndZ + 9, 40, 1, scanned("forest/bucket"));
    }

    /** Walks north from the clearing along x until the ground dips under the water. */
    private float findShore(float x) {
        for (float z = CLEARING_Z; z > LAKE_Z; z -= 0.5f)
            if (heightAt(x, z) < WATER + 0.3f) return z;
        return LAKE_Z + LAKE_RADIUS_Z;
    }

    private void addTrees() {
        String[] conifers = {"fir_a", "fir_a", "fir_b", "fir_b", "fir_c", "pine_a", "pine_b"};
        String[] edge = {"broadleaf_a", "broadleaf_b", "broadleaf_b", "fir_c", "pine_b"};

        // hand-placed around the clearing so the view stays open toward the lake and the sun
        float[][] framing = {{1118, 1228, 0}, {1108, 1290, 1}, {1140, 1340, 0}, {1196, 1372, 0}, {1262, 1366, 1}, {1312, 1320, 0}, {1322, 1252, 1}, {1304, 1196, 0}, {1132, 1186, 1}};
        for (float[] f : framing)
            tree(f[2] == 1 ? edge[random.nextInt(2)] : conifers[random.nextInt(conifers.length)], f[0], f[1], 0.95f + random.nextFloat() * 0.3f, random.nextFloat() * 360);

        // the forest: dense near the scene, thinning out over the hills where the haze takes over
        scatter(760, 1660, 640, 1720, 250, 15, (x, z) -> {
            if (!forest(x, z)) return false;
            float fromCentre = (float) Math.hypot(x - CENTRE_X, z - CENTRE_Z);
            if (fromCentre > 360 && random.nextFloat() < (fromCentre - 360) / 520f) return false;
            boolean nearOpenGround = meadow(x, z) > 0.02f || lakeDistance(x, z) < 1.28f;
            String[] kinds = nearOpenGround && random.nextFloat() < 0.6f ? edge : conifers;
            tree(kinds[random.nextInt(kinds.length)], x, z, 0.85f + random.nextFloat() * 0.45f, random.nextFloat() * 360);
            return true;
        });

        // young firs and bushes where light reaches the ground
        scatter(980, 1420, 900, 1460, 22, 9, (x, z) -> {
            if (!forest(x, z) || meadow(x, z) < 0.001f && lakeDistance(x, z) > 1.45f) return false;
            tree("fir_young", x, z, 0.7f + random.nextFloat() * 0.6f, random.nextFloat() * 360);
            return true;
        });
        scatter(980, 1420, 900, 1460, 34, 8, (x, z) -> {
            if (heightAt(x, z) < WATER + 1.2f || meadow(x, z) > 0.55f || trodden(x, z) > 0.05f) return false;
            if (meadow(x, z) < 0.001f && lakeDistance(x, z) > 1.4f) return false;
            tree(random.nextBoolean() ? "bush_a" : "bush_b", x, z, 0.8f + random.nextFloat() * 0.6f, random.nextFloat() * 360);
            return true;
        });
    }

    /** Ground where trees stand: dry, outside the clearing, and not in the open strip between the clearing and the lake. */
    private static boolean forest(float x, float z) {
        if (heightAt(x, z) < WATER + 2.2f || meadow(x, z) > 0.25f) return false;
        boolean sunCorridor = z < CLEARING_Z && z > LAKE_Z + 60 && Math.abs(x - (CLEARING_X - 14)) < 78;
        return !sunCorridor;
    }

    /** A tree is two entities sharing a spot: the bark-textured trunk and the cut-out foliage. Both sway together. */
    private Entity tree(String kind, float x, float z, float scale, float rotY) {
        boolean broadleaf = kind.startsWith("broadleaf") || kind.startsWith("bush");
        String name = next(kind.startsWith("fir") ? "fir" : kind.startsWith("pine") ? "pine" : kind.startsWith("bush") ? "bush" : "broadleaf tree");
        String foliage = kind.startsWith("fir") ? "forest/foliage_fir" : kind.startsWith("pine") ? "forest/foliage_pine" : "forest/foliage_leaves";
        float y = height(x, z) - 0.3f;
        at(name, "forest/" + kind + "_trunk", broadleaf ? "forest/bark_broadleaf" : "forest/bark_conifer", x, y, z, rotY, scale, broadleaf ? BARK_BROADLEAF : BARK_CONIFER);
        return at(name + " foliage", "forest/" + kind + "_leaves", foliage, x, y, z, rotY, scale, broadleaf ? LEAVES : NEEDLES);
    }

    private void addRocksAndDeadwood() {
        String[] rocks = {"mossrock_a", "mossrock_b", "mossrock_c", "mossrock_d"};
        // along the shore, half in the water
        scatter(1000, 1400, 900, 1200, 12, 22, (x, z) -> {
            float h = heightAt(x, z);
            if (h < WATER - 2.5f || h > WATER + 1.5f || Math.abs(x - JETTY_X) < 16) return false;
            prop(next("shore rock"), "forest/" + rocks[random.nextInt(4)], "forest/mossrock", x, z, -1.2f, random.nextFloat() * 360, 0.8f + random.nextFloat() * 1.3f);
            return true;
        });
        // in the forest and at the clearing's edge
        scatter(1000, 1420, 1150, 1440, 16, 20, (x, z) -> {
            if (heightAt(x, z) < WATER + 2 || meadow(x, z) > 0.6f || trodden(x, z) > 0.02f) return false;
            prop(next("mossy rock"), "forest/" + rocks[random.nextInt(4)], "forest/mossrock", x, z, -0.9f, random.nextFloat() * 360, 0.6f + random.nextFloat() * 1.2f);
            return true;
        });
        // on top of the bank west of the jetty, along the shore; and one at the far edge of the clearing
        layLog("fallen log", 1152, shoreZ + 24, 15, 1.5f, 8);
        layLog("fallen log", 1288, 1300, 20, 1.3f, 5);
        scatter(1020, 1400, 1160, 1420, 7, 25, (x, z) -> {
            if (!forest(x, z)) return false;
            prop(next("stump"), "demo/real/stump", x, z, -0.5f, random.nextFloat() * 360, 0.4f + random.nextFloat() * 0.3f);
            return true;
        });
    }

    /**
     * A log lying on the ground. The ground is sampled along its whole length: the log is set on the line that fits
     * those heights best and tilted to match, so neither end hangs in the air.
     *
     * @param heading degrees; the log's thick end points along (cos, -sin) of it
     * @param wiggle  how far (in units, and up to 30 degrees of heading) the log may be moved to find the spot
     *                where the ground under it is straightest; 0 = exactly as given
     */
    private Entity layLog(String name, float x, float z, float heading, float scale, float wiggle) {
        final float halfLength = 7.5f * scale; // the model is 16 long
        final float[] along = {-1, -0.5f, 0, 0.5f, 1};
        float bestScore = Float.MAX_VALUE, bestX = x, bestZ = z, bestHeading = heading, bestHeight = height(x, z), bestSlope = 0;
        int steps = wiggle > 0 ? 2 : 0;
        for (int ix = -steps; ix <= steps; ix++)
            for (int iz = -steps; iz <= steps; iz++)
                for (int ih = -steps; ih <= steps; ih++) {
                    float cx = x + (steps == 0 ? 0 : ix * wiggle / steps), cz = z + (steps == 0 ? 0 : iz * wiggle / steps), h = heading + ih * 15;
                    float dirX = (float) Math.cos(Math.toRadians(h)), dirZ = (float) -Math.sin(Math.toRadians(h));
                    float[] heights = new float[along.length];
                    float mean = 0, sumSH = 0, sumSS = 0, lowest = Float.MAX_VALUE;
                    for (int i = 0; i < along.length; i++) {
                        float s = along[i] * halfLength;
                        heights[i] = height(cx + dirX * s, cz + dirZ * s);
                        lowest = Math.min(lowest, heights[i]);
                        mean += heights[i] / along.length;
                        sumSH += s * heights[i];
                        sumSS += s * s;
                    }
                    float slope = sumSH / sumSS, gap = 0;
                    for (int i = 0; i < along.length; i++) gap = Math.max(gap, Math.abs(heights[i] - (mean + slope * along[i] * halfLength)));
                    // prefer straight ground, dry land, and staying close to where it was asked for
                    float score = gap + (lowest < WATER + 1 ? 100 : 0) + 0.02f * (Math.abs(ix) + Math.abs(iz) + Math.abs(ih));
                    if (score < bestScore) {
                        bestScore = score;
                        bestX = cx;
                        bestZ = cz;
                        bestHeading = h;
                        bestHeight = mean;
                        bestSlope = slope;
                    }
                }
        // the model's underside is at its origin; bed it a little way into the ground
        Entity log = at(name, "forest/log", "forest/log", bestX, bestHeight - 0.22f * scale, bestZ, bestHeading, scale, scanned("forest/log"));
        log.setRotZ(degrees(Math.atan(bestSlope))); // Z is applied before the heading: it lifts the log's +x end
        return log;
    }

    /** True if a patch of this radius would cover part of the trodden earth around the fire or the way to the jetty. */
    private static boolean onPath(float x, float z, float radius) {
        float r = radius * 0.75f;
        return trodden(x, z) > 0.2f || trodden(x + r, z) > 0.45f || trodden(x - r, z) > 0.45f || trodden(x, z + r) > 0.45f || trodden(x, z - r) > 0.45f;
    }

    /**
     * A grass patch (a flat disc of tufts) laid on the slope. Refuses spots where the ground curves too much
     * within the patch for a flat disc to follow it, such as the edge of the bank: tufts would hang in the air.
     */
    private boolean grass(String name, String model, float x, float z, float radius, float scale) {
        GroundFit ground = fitGround(x, z, radius * scale);
        if (ground.unevenness() > 0.3f) return false;
        onSlope(next(name), "forest/" + model, "forest/grass", ground, x, z, -0.1f, random.nextBoolean(), scale, GRASS);
        return true;
    }

    private void addUndergrowth() {
        // meadow grass, some of it flowering; every patch is one batch-friendly mesh of 20-odd tufts
        scatter(1090, 1340, 1150, 1390, 150, 7.5f, (x, z) -> {
            float scale = 0.85f + random.nextFloat() * 0.4f;
            if (meadow(x, z) < 0.35f || onPath(x, z, 6.5f * scale) || heightAt(x, z) < WATER + 1.3f) return false;
            String model = random.nextFloat() < 0.22f ? "grass_flowers" : random.nextBoolean() ? "grass_patch_a" : "grass_patch_b";
            return grass("meadow grass", model, x, z, 6.5f, scale);
        });
        // clumps on the forest floor
        scatter(1020, 1400, 1130, 1430, 55, 6, (x, z) -> {
            float scale = 0.8f + random.nextFloat() * 0.6f;
            if (meadow(x, z) > 0.4f || heightAt(x, z) < WATER + 1.5f || onPath(x, z, 1.6f * scale)) return false;
            return grass("grass clump", "grass_clump", x, z, 1.6f, scale);
        });
        // sedge standing along the waterline, about waist to chest high; none around the jetty or on the way to it
        scatter(980, 1400, 880, 1210, 70, 3.5f, (x, z) -> {
            float h = heightAt(x, z), scale = 0.4f + random.nextFloat() * 0.3f;
            if (h < WATER - 0.8f || h > WATER + 1.1f || Math.abs(x - JETTY_X) < 14 || onPath(x, z, 3)) return false;
            return grass("sedge", "sedge", x, z, 2.2f, scale);
        });
        // ferns and herbs in the shade
        String[] ferns = {"fern_a", "fern_b", "fern_c"};
        scatter(1030, 1400, 1140, 1430, 70, 5, (x, z) -> {
            if (meadow(x, z) > 0.3f || heightAt(x, z) < WATER + 2 || trodden(x, z) > 0.02f) return false;
            onGround(next("fern"), "forest/" + ferns[random.nextInt(3)], "forest/fern", x, z, -0.05f, random.nextFloat() * 360, 1.3f + random.nextFloat() * 1.1f, FERN);
            return true;
        });
        scatter(1060, 1380, 1160, 1400, 24, 5, (x, z) -> {
            if (heightAt(x, z) < WATER + 1.6f || trodden(x, z) > 0.02f || meadow(x, z) > 0.8f) return false;
            onGround(next("herb"), random.nextBoolean() ? "forest/herb_a" : "forest/herb_b", "forest/herb", x, z, -0.05f, random.nextFloat() * 360, 0.9f + random.nextFloat() * 0.6f, FERN);
            return true;
        });
    }

    private void addAmbientEffects() {
        // dust and pollen hanging in the sunlit air of the clearing
        ParticleSystem motes = particles("particles/forest/mote.png", 4, 1.8f, 30, 0.5f, 7, 0.24f, 0);
        emitters.add(new Emitter(motes, new Vector3f(CLEARING_X - 10, height(CLEARING_X, CLEARING_Z) + 8, CLEARING_Z - 30), 70, 7, 60));

        // mist drifting low over the lake
        // (its puffs are far larger than their height above the lake, so they fade out toward the water surface)
        ParticleSystem mist = new ParticleSystem(new ParticleTexture(texture("particles/forest/mist.png"), 4, 1, 9), 2.6f, 0.7f, 16, 70);
        mist.setGravityEffect(0);
        mist.setSpeedError(0.3f);
        mist.setLifeError(0.25f);
        mist.setScaleError(0.35f);
        mist.randomizeRotation();
        mist.setDirection(new Vector3f(0.86f, 0.03f, 0.5f), 0.25f);
        emitters.add(new Emitter(mist, new Vector3f(LAKE_X - 10, WATER + 5, LAKE_Z), 125, 1.5f, 70)); // kept over the water

        // fireflies in the sedge along the south shore and at the dark edge of the wood
        ParticleSystem fireflies = particles("particles/demo/firefly.png", 4, 2.6f, 6, 1.3f, 5, 0.75f, 0);
        emitters.add(new Emitter(fireflies, new Vector3f(JETTY_X - 15, WATER + 3.5f, shoreZ + 4), 75, 2.5f, 14));
        ParticleSystem woodFireflies = particles("particles/demo/firefly.png", 4, 2.6f, 4, 1.2f, 5, 0.75f, 0);
        emitters.add(new Emitter(woodFireflies, new Vector3f(1118, height(1118, 1262) + 4, 1262), 22, 3, 60));
    }

    // ------------------------------------------------------------------------------------------------------------

    /** A scanned prop: colour texture plus normal map with the same name. */
    private static Look scanned(String texture) {
        return new Look(0.1f, 8, false, false, 1).normalMap(texture + "_normal");
    }

    private Entity prop(String name, String model, float x, float z, float yOffset, float rotY, float scale) {
        return prop(name, model, model, x, z, yOffset, rotY, scale);
    }

    private Entity prop(String name, String model, float x, float z, float yOffset, float rotY, float scale, Look look) {
        return onGround(name, model, model, x, z, yOffset, rotY, scale, look);
    }

    private Entity prop(String name, String model, String texture, float x, float z, float yOffset, float rotY, float scale) {
        return onGround(name, model, texture, x, z, yOffset, rotY, scale, scanned(texture));
    }
}
