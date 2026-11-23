package engineTester;

import entities.Camera;
import entities.Entity;
import entities.Light;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.vector.Vector3f;
import particles.ParticleSystem;
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
import java.util.Map;

/**
 * "village": a low-poly lakeside village in a ring of wooded hills, for demo videos. Every engine feature has
 * its own spot, and everything is built from flat-coloured CC0 kit models on ground made
 * to match them: large flat-shaded facets in flat colours.
 *
 * <pre>
 *                               north (-z)
 *            hills and rocky peaks all around the valley
 *      [NORTH LAKE: the ship sails a slow loop]            reflection / refraction / sun glints
 *                   |      [dock, fisher, rowboat]
 *   [forest         |               [windmill on its knoll]   spinning rotor (entity animation)
 *    + camp] ---- bridge ---- [watermill]                     wheel turning in the river
 *                   |      [VILLAGE: fountain plaza, lamp, market, tavern]
 *                   |                                         spray + chimney-smoke particles, draggable lamp light
 *        [SOUTH POND: lily pads, reeds, fireflies]
 *                              [SHRINE HILL: crystal, columns, braziers]   specular + violet light + sparkles
 *                               south (+z)
 * </pre>
 *
 * The land is designed, not random: heightAt() below shapes the valley around the landmarks, and the blend maps
 * (ground colours, paths) are painted from the same function when the scene loads. To move a landmark, change its
 * constant: the level ground under it, its path and the clearing around it follow.
 *
 * Only 3 lights reach the shaders (MAX_LIGHTS): the sun, the lamp light and the shrine light. The sun (first
 * light) is the one that casts shadows. Rendering features are toggled with the number keys (GraphicsSettings).
 */
public class DemoScene extends SceneBase {

    // dragging the lamp also drags its glass and its light (see entityDragged)
    public static final String LAMP_NAME = "lamp";
    public static final String LAMP_GLASS_NAME = "lamp glass";
    public static final String LAMP_LIGHT_NAME = "lampLight";
    /** Where the lantern hangs relative to the foot of the lamp post: this high, and this far along the post's arm. The lamp light sits there. */
    public static final float LAMP_LIGHT_HEIGHT = 15.7f, LAMP_ARM = 3.9f;

    private static final float WATER = WaterTile.WATER_HEIGHT;
    private static final String ATLAS = "demo/kenney_atlas";

    // ---- the ground: 16-unit facets (the engine default is 8-unit squares, too small to read as low-poly)
    private static final int GRID = 51;
    private static final float CELL = Terrain.SIZE / (float) (GRID - 1);
    // flat ground colours in the same family as the kit models' atlas
    private static final float[] MEADOW = {0.55f, 0.74f, 0.37f}, WOODLAND = {0.37f, 0.60f, 0.34f}, SAND = {0.92f, 0.83f, 0.59f},
            PATH = {0.83f, 0.69f, 0.48f}, ROCK = {0.63f, 0.64f, 0.67f};

    // ---- landmarks (world x, z)
    private static final float PLAZA_X = 1320, PLAZA_Z = 1238;
    private static final float LAMP_X = 1297, LAMP_Z = 1226;
    private static final float SHRINE_X = 1385, SHRINE_Z = 1400;
    private static final float MILL_X = 1277.6f, MILL_Z = 1184, WHEEL_X = MILL_X - 18.6f; // the wheel hangs on the mill's river side
    private static final float WINDMILL_X = 1352, WINDMILL_Z = 1148;
    private static final float CAMP_X = 1146, CAMP_Z = 1198;
    private static final float BRIDGE_X = 1248, BRIDGE_Z = 1136, BRIDGE_HALF_LENGTH = 34;
    private static final float DOCK_X = 1300;

    // ---- land levels
    private static final float BANK = WATER + 5.5f;          // the village, the mill yard and both ends of the bridge
    private static final float KNOLL_TOP = WATER + 17, SHRINE_TOP = WATER + 21, CAMP_LEVEL = WATER + 8.5f, DOCK_LEVEL = WATER + 3;

    // ---- water: the north lake (with a bay for the dock), the south pond and the river that joins them
    private static final float LAKE_X = 1215, LAKE_Z = 950, LAKE_RADIUS_X = 185, LAKE_RADIUS_Z = 110;
    private static final float BAY_X = 1304, BAY_Z = 1044, BAY_RADIUS = 30;
    private static final float POND_X = 1192, POND_Z = 1378, POND_RADIUS_X = 72, POND_RADIUS_Z = 92;
    private static final float[] RIVER = {1250, 1040, BRIDGE_X, 1100, BRIDGE_X, 1200, 1234, 1250, 1208, 1300}; // centre line, x z pairs

    // ---- the ring of hills: the valley floor ends about 320 units from here (the ring is a little narrower east-west)
    private static final float VALLEY_X = 1235, VALLEY_Z = 1140;
    /** Rounded hills on the ring: {x, z, radius, height}. */
    private static final float[][] HILLS = {{1250, 590, 230, 72}, {1620, 760, 220, 90}, {1760, 1150, 240, 68}, {1640, 1520, 220, 80},
            {1280, 1700, 240, 86}, {880, 1560, 230, 72}, {740, 1180, 240, 90}, {860, 780, 230, 76}};
    /** Pointed summits on top of three of them: {x, z, radius, height}. */
    private static final float[][] PEAKS = {{1630, 750, 110, 46}, {1290, 1705, 120, 40}, {735, 1170, 120, 44}};

    /** Level ground for things to stand on: {x, z, radius that is fully level, radius where the land is itself again, height}. */
    private static final float[][] LEVEL_GROUND = {
            {PLAZA_X + 2, PLAZA_Z + 2, 56, 88, BANK},
            {MILL_X + 8, MILL_Z, 24, 44, BANK},
            {WINDMILL_X, WINDMILL_Z, 22, 34, KNOLL_TOP},
            {SHRINE_X, SHRINE_Z, 57, 68, SHRINE_TOP},
            {CAMP_X, CAMP_Z, 26, 50, CAMP_LEVEL},
            {DOCK_X, 1090, 12, 34, DOCK_LEVEL},
            {BRIDGE_X - BRIDGE_HALF_LENGTH - 6, BRIDGE_Z, 12, 26, BANK},
            {BRIDGE_X + BRIDGE_HALF_LENGTH + 6, BRIDGE_Z, 12, 26, BANK}};

    /** Paths as lines of world x,z pairs; painted into the blend map and kept free of plants. */
    private static final float[][] PATHS = {
            {PLAZA_X, PLAZA_Z, 1306, 1212, 1300, 1172, 1294, 1148, BRIDGE_X + BRIDGE_HALF_LENGTH, BRIDGE_Z},   // plaza -> bridge
            {BRIDGE_X - BRIDGE_HALF_LENGTH, BRIDGE_Z, 1192, 1146, 1166, 1168, CAMP_X, CAMP_Z},                 // bridge -> forest camp
            {BRIDGE_X + BRIDGE_HALF_LENGTH, BRIDGE_Z, 1294, 1112, DOCK_X, 1084},                               // bridge -> dock
            {PLAZA_X, PLAZA_Z, 1338, 1205, 1348, 1172, WINDMILL_X, WINDMILL_Z + 12},                           // plaza -> windmill
            {PLAZA_X, PLAZA_Z, 1350, 1264, 1362, 1300, 1370, 1340, 1378, 1374},                                // plaza -> shrine
    };
    private static final float PATH_WIDTH = 12;
    /** Round patches of the same trodden ground: {x, z, radius}. */
    private static final float[][] TRODDEN = {{PLAZA_X, PLAZA_Z, 30}, {SHRINE_X, SHRINE_Z, 34}, {CAMP_X, CAMP_Z, 15}, {MILL_X + 22, MILL_Z + 4, 9}, {DOCK_X, 1084, 8}};
    /** Building plots that scattered plants must stay off: {x, z, radius}. */
    private static final float[][] PLOTS = {{MILL_X, MILL_Z, 27}, {WINDMILL_X, WINDMILL_Z, 24}, {PLAZA_X - 36, PLAZA_Z + 20, 24}, {PLAZA_X + 46, PLAZA_Z - 6, 24},
            {PLAZA_X - 2, PLAZA_Z + 52, 28}, {BRIDGE_X - BRIDGE_HALF_LENGTH - 6, BRIDGE_Z, 24}, {BRIDGE_X + BRIDGE_HALF_LENGTH + 6, BRIDGE_Z, 24}, {DOCK_X, 1070, 16}, {CAMP_X, CAMP_Z, 33}};

    // ---- the ship: sails clockwise round an ellipse on the north lake, bow first
    private static final float ROUTE_X = 1196, ROUTE_Z = 950, ROUTE_RADIUS_X = 112, ROUTE_RADIUS_Z = 52, SHIP_SPEED = 9;
    private static final float SHIP_DRAUGHT = 2.4f, SHIP_STERN = 17;
    /**
     * B-roll shots that film the ship, with the second of its lap (one lap is about a minute) at which each starts.
     * While one of them plays the ship is placed from the time within the shot, so it is in the same place in live
     * playback, in previews and in recordings; at all other times it follows the world clock.
     */
    private static final Map<String, Float> SHIP_SHOTS = Map.of("boat_passing", 5.2f, "dock_fisher", -2.5f);
    private static final float WAKE_SECONDS = 3.5f;

    public DemoScene() {
        super(7);
    }

    private Light sun, lampLight;
    private Entity windmillBlades, waterWheel, lampPost, lampGlass, ship;
    private final float[] routeDistance = new float[361]; // distance sailed after each degree of the ellipse
    private final Vector3f wakeOrigin = new Vector3f();
    private String shot;
    private float shotSeconds;

    // ------------------------------------------------------------------------------------------------------------
    // environment + terrain

    @Override
    public void configureEnvironment() {
        Environment.terrainVertexCount = GRID;
        Environment.terrainDiagonals = 2;              // facet edges follow shores and ridges
        Environment.terrainFlatShading = true;
        Environment.terrainFacetVariation = 0.035f;
        Environment.terrainExtraColour.set(ROCK[0], ROCK[1], ROCK[2]);
        // clear air and a long view: from high above the village the hills on the far side of the valley are
        // 900 units away and still stand out; the land beyond them fades out before the far plane cuts it off
        Environment.fogDensity = 0.00085f;
        Environment.farPlane = 1500;
        // water as flat-coloured as the land: it turns a clear blue within a few units of depth instead of showing its bed
        Environment.waterMurkiness = 0.32f;
        Environment.waterDeepColour.set(0.10f, 0.40f, 0.56f);
    }

    @Override
    public HeightSource terrainHeights() {
        return DemoScene::heightAt;
    }

    /**
     * The valley: a nearly level floor by the river, the two water bodies and the river carved into it, a knoll
     * for the windmill, a flat-topped hill for the shrine, level ground where things stand, and a closed ring of
     * hills with a few pointed peaks. Bold forms and little noise: the facets are 16 units wide.
     */
    private static float heightAt(float x, float z) {
        // valley floor, rising gently away from the river on both sides, with long shallow swells
        float land = BANK + Math.max(0, x - 1300) * 0.035f + Math.max(0, 1200 - x) * 0.05f;
        land += (fbm(x * 0.011f, z * 0.011f) - 0.5f) * 7;

        float ring = ring(x, z);
        land += ring * 40;
        for (float[] h : HILLS) {
            float t = distance(x, z, h[0], h[1]) / h[2];
            if (t < 1) land += h[3] * (1 - t * t) * (1 - t * t);
        }
        for (float[] p : PEAKS) {
            float t = distance(x, z, p[0], p[1]) / p[2];
            if (t < 1) land += p[3] * (float) Math.pow(1 - t, 1.7);
        }

        land = mix(land, KNOLL_TOP, 1 - smoothstep(24, 66, distance(x, z, WINDMILL_X, WINDMILL_Z)));
        land = mix(land, SHRINE_TOP, 1 - smoothstep(58, 128, distance(x, z, SHRINE_X, SHRINE_Z) * (0.92f + 0.16f * noise(x * 0.02f, z * 0.02f))));

        float level = 0;
        for (float[] g : LEVEL_GROUND) {
            float amount = 1 - smoothstep(g[2], g[3], distance(x, z, g[0], g[1]));
            land = mix(land, g[4], amount);
            level = Math.max(level, amount);
        }

        // water: banks drop from 10 units outside the shoreline to the bed 14 units inside; lakes deepen further in
        float shore = shoreDistance(x, z);
        float bed = WATER - 3 - 6.5f * smoothstep(0, 45, -shore);
        land = mix(land, bed, 1 - smoothstep(-14, 10, shore));
        // a deep pool beside the mill, so the wheel has water under it right up to the wall
        float race = Math.max(Math.abs(x - WHEEL_X) - 7, Math.abs(z - MILL_Z) - 18);
        land = Math.min(land, mix(WATER - 6, land, smoothstep(0, 4, race)));

        // every grid point a little up or down, or neighbouring facets would be lit alike and vanish; more on the hills
        float rough = (1 - level) * (1 + 0.9f * ring) * (shore < 0 ? 0.5f : 1);
        return land + (noise(x * 0.071f + 3, z * 0.071f + 11) - 0.5f) * 4 * rough;
    }

    /** 0 on the valley floor, 1 on the hills around it. */
    private static float ring(float x, float z) {
        float fromCentre = (float) Math.hypot((x - VALLEY_X) * 1.12f, z - VALLEY_Z) * (0.86f + 0.28f * fbm(x * 0.0035f + 9, z * 0.0035f + 4));
        return smoothstep(320, 620, fromCentre);
    }

    /** Roughly the distance to the nearest shoreline: negative in the water, positive on land. */
    private static float shoreDistance(float x, float z) {
        float lake = ovalDistance(x, z, LAKE_X, LAKE_Z, LAKE_RADIUS_X, LAKE_RADIUS_Z, 0.3f);
        float bay = distance(x, z, BAY_X, BAY_Z) - BAY_RADIUS;
        float pond = ovalDistance(x, z, POND_X, POND_Z, POND_RADIUS_X, POND_RADIUS_Z, 0.24f);
        // the river is 24 wide each side of its centre line at the bridge and the mill, wider where it meets lake and pond
        float halfWidth = 24 + 9 * smoothstep(1100, 1060, z) + 8 * smoothstep(1235, 1285, z);
        float river = Float.MAX_VALUE;
        for (int i = 0; i + 3 < RIVER.length; i += 2) river = Math.min(river, segmentDistance(x, z, RIVER[i], RIVER[i + 1], RIVER[i + 2], RIVER[i + 3]));
        return Math.min(Math.min(lake, bay), Math.min(pond, river - halfWidth));
    }

    /** Distance to the edge of an oval whose outline wobbles, so the shore isn't a perfect ellipse. */
    private static float ovalDistance(float x, float z, float centreX, float centreZ, float radiusX, float radiusZ, float wobble) {
        float dx = (x - centreX) / radiusX, dz = (z - centreZ) / radiusZ;
        float radii = (float) Math.sqrt(dx * dx + dz * dz) + (fbm(x * 0.012f + 20, z * 0.012f + 7) - 0.5f) * wobble; // 1 on the outline
        float fromCentre = distance(x, z, centreX, centreZ);
        return radii < 0.05f ? -Math.min(radiusX, radiusZ) : fromCentre - fromCentre / radii;
    }

    // ------------------------------------------------------------------------------------------------------------
    // ground colours

    /** Flat colours instead of photos: background = meadow, red = sand, green = darker green under the woods, blue = paths. Rock is the terrain's extra colour. */
    @Override
    public TerrainTexturePack terrainTextures() {
        return new TerrainTexturePack(flatColour(MEADOW), flatColour(SAND), flatColour(WOODLAND), flatColour(PATH));
    }

    private static TerrainTexture flatColour(float[] rgb) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(4 * 4 * 4);
        for (int i = 0; i < 16; i++) pixels.put((byte) Math.round(rgb[0] * 255)).put((byte) Math.round(rgb[1] * 255)).put((byte) Math.round(rgb[2] * 255)).put((byte) 255);
        pixels.flip();
        return new TerrainTexture(Loader.loadTexture(4, 4, pixels));
    }

    /**
     * Painted from the terrain when the scene loads. Heights are taken on the facets the terrain is drawn with
     * (same grid, same diagonals), so the sand line and the rock follow the facet edges exactly.
     * Red = sand, green = woodland, blue = path, alpha below 1 = rock.
     */
    @Override
    public TerrainTexture blendMap(int tileX, int tileZ) {
        float[][] grid = new float[GRID][GRID];
        for (int i = 0; i < GRID; i++)
            for (int j = 0; j < GRID; j++)
                grid[i][j] = heightAt(tileX * Terrain.SIZE + i * CELL, tileZ * Terrain.SIZE + j * CELL);

        int size = tileX == 1 && tileZ == 1 ? 1024 : 384; // the valley is on the centre tile
        ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
        for (int py = 0; py < size; py++)
            for (int px = 0; px < size; px++) {
                float u = (px + 0.5f) / size * (GRID - 1), v = (py + 0.5f) / size * (GRID - 1);
                int gx = Math.min((int) u, GRID - 2), gz = Math.min((int) v, GRID - 2);
                float fx = u - gx, fz = v - gz, x = tileX * Terrain.SIZE + u * CELL, z = tileZ * Terrain.SIZE + v * CELL;
                float topLeft = grid[gx][gz], topRight = grid[gx + 1][gz], bottomLeft = grid[gx][gz + 1], bottomRight = grid[gx + 1][gz + 1];

                // height and slope of the triangle this pixel lies on
                float height, slopeX, slopeZ;
                if (Terrain.splitsOtherWay(topLeft, topRight, bottomLeft, bottomRight, gx, gz)) {
                    slopeX = fx >= fz ? topRight - topLeft : bottomRight - bottomLeft;
                    slopeZ = fx >= fz ? bottomRight - topRight : bottomLeft - topLeft;
                    height = topLeft + slopeX * fx + slopeZ * fz;
                } else if (fx + fz <= 1) {
                    slopeX = topRight - topLeft;
                    slopeZ = bottomLeft - topLeft;
                    height = topLeft + slopeX * fx + slopeZ * fz;
                } else {
                    slopeX = bottomRight - bottomLeft;
                    slopeZ = bottomRight - topRight;
                    height = bottomRight - slopeX * (1 - fx) - slopeZ * (1 - fz);
                }
                float steepness = (float) Math.hypot(slopeX, slopeZ) / CELL;

                float path = 1 - smoothstep(-0.7f, 0.7f, pathDistance(x, z));
                float sand = (1 - smoothstep(WATER + 1.0f, WATER + 1.6f, height)) * (1 - path);
                float rock = rockiness(x, z, height, steepness) * (1 - path) * (1 - sand);
                float woods = woodland(x, z) * (1 - path) * (1 - sand) * (1 - rock);
                pixels.put((byte) (sand * 255)).put((byte) (woods * 255)).put((byte) (path * 255)).put((byte) ((1 - rock) * 255));
            }
        pixels.flip();
        return new TerrainTexture(Loader.loadTexture(size, size, pixels));
    }

    /** 1 where bare rock shows: the summits, and steep faces on the hills. */
    private static float rockiness(float x, float z, float height, float steepness) {
        float summit = smoothstep(-3, 3, height - (WATER + 112 + (fbm(x * 0.01f + 2, z * 0.01f + 6) - 0.5f) * 44));
        float cliff = smoothstep(0.85f, 0.95f, steepness) * smoothstep(WATER + 28, WATER + 40, height);
        return Math.max(summit, cliff);
    }

    /** 1 under the woods: the whole west bank and the slopes of the ring, with a ragged edge. */
    private static float woodland(float x, float z) {
        float ragged = (fbm(x * 0.03f + 3, z * 0.03f + 8) - 0.5f);
        float westBank = smoothstep(1208, 1186, x + ragged * 44) * (1 - smoothstep(18, 30, 48 - distance(x, z, CAMP_X, CAMP_Z)));
        float slopes = smoothstep(0.05f, 0.11f, ring(x, z) + ragged * 0.12f);
        return Math.max(westBank, slopes);
    }

    /** Distance to the nearest path or trodden patch; negative on it. */
    private static float pathDistance(float x, float z) {
        if (x < 1100 || x > 1450 || z < 1050 || z > 1470) return 100; // nowhere near one
        float distance = Float.MAX_VALUE;
        for (float[] path : PATHS)
            for (int i = 0; i + 3 < path.length; i += 2)
                distance = Math.min(distance, segmentDistance(x, z, path[i], path[i + 1], path[i + 2], path[i + 3]) - PATH_WIDTH / 2);
        for (float[] patch : TRODDEN) distance = Math.min(distance, distance(x, z, patch[0], patch[1]) - patch[2]);
        return distance + (noise(x * 0.09f, z * 0.09f) - 0.5f) * 2.2f;
    }

    // ------------------------------------------------------------------------------------------------------------
    // contents

    /** Adds every demo entity, light and water tile. Call before the Editor is created so they show up in it. */
    @Override
    public void populate(Terrain[][] terrains, List<Entity> entities, List<Light> lights, List<WaterTile> waterTiles) {
        begin(terrains, entities);
        addLights(lights);
        addVillage();
        addMills();
        addShrine();
        addWaterside();
        addCamp();
        addForests();
        addGroundCover();
        addHillForests();

        // one big tile floods everything below the water level, so shorelines follow the terrain (no square edges)
        waterTiles.add(new WaterTile(Terrain.SIZE * 1.5f, Terrain.SIZE * 1.5f, WATER, Terrain.SIZE * 1.5f));
    }

    /** Opening shot: above the river, looking south-east over the bridge, mills and village toward the shrine hill. */
    @Override
    public void setUpCamera(Camera camera) {
        camera.getFocusPoint().getPosition().set(1300, WATER, 1215);
        camera.setAngleAroundFocusPoint(40);
        camera.setAngleAboveFocusPoint(24);
        camera.setDistanceFromFocusPoint(230);
    }

    /** Per-frame motion that doesn't need any input: turning mills, the ship and the ambient particle effects. */
    @Override
    public void update() {
        float delta = DisplayManager.getFrameTimeSeconds();
        windmillBlades.setRotZ((windmillBlades.getRotZ() + 28 * delta) % 360);
        waterWheel.setRotZ((waterWheel.getRotZ() + 40 * delta) % 360);

        // the glass is part of the lamp: wherever the post is moved or turned (drag or editor), it goes along
        lampGlass.getPosition().set(lampPost.getPosition());
        lampGlass.setRotY(lampPost.getRotY());

        sailShip();
        runEmitters();
    }

    @Override
    public Light getSun() {
        return sun;
    }

    /** Dragging the lamp post (or its glass) takes the whole lamp and its light along. */
    @Override
    public void entityDragged(Entity entity, Vector3f position, List<Light> lights) {
        if (entity != lampPost && entity != lampGlass) return;
        lampPost.getPosition().set(position);
        for (Light light : lights) {
            if (LAMP_LIGHT_NAME.equals(light.getName())) light.getPosition().set(lanternPosition());
        }
    }

    /** The lantern hangs from the end of the post's arm, which points along the model's +z. */
    private Vector3f lanternPosition() {
        double heading = Math.toRadians(lampPost.getRotY());
        Vector3f foot = lampPost.getPosition();
        return new Vector3f(foot.x + (float) Math.sin(heading) * LAMP_ARM, foot.y + LAMP_LIGHT_HEIGHT, foot.z + (float) Math.cos(heading) * LAMP_ARM);
    }

    @Override
    public void shotTime(String shotName, float seconds) {
        shot = shotName;
        shotSeconds = seconds;
    }

    /** The wake behind the ship has to exist already when a shot of the ship starts. */
    @Override
    public float shotLeadIn(String shotName) {
        return SHIP_SHOTS.containsKey(shotName) ? WAKE_SECONDS + 1 : 0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // lights

    private void addLights(List<Light> lights) {
        // far away + no attenuation = directional light. It stands in the west, to the right of the opening camera,
        // so walls facing the camera are lit and shadows fall across the scene. It is also the light that casts
        // shadows and makes the glints on the water (look west over the pond to see them).
        sun = new Light(new Vector3f(-1500, 2480, 1640), new Vector3f(1.0f, 0.95f, 0.85f), new Vector3f(1, 0, 0));
        sun.setName("sun");
        lights.add(sun);

        // warm pool of light around the lamp post; strong enough to read in daylight. Moved to the lantern in addVillage.
        lampLight = new Light(new Vector3f(LAMP_X, height(LAMP_X, LAMP_Z) + LAMP_LIGHT_HEIGHT, LAMP_Z),
                new Vector3f(1.0f, 0.75f, 0.3f), new Vector3f(1, 0.004f, 0.0007f));
        lampLight.setName(LAMP_LIGHT_NAME);
        lights.add(lampLight);

        // violet accent in front of the crystal: coloured diffuse + specular on a shiny surface
        Light shrineLight = new Light(new Vector3f(SHRINE_X - 22, height(SHRINE_X, SHRINE_Z) + 24, SHRINE_Z - 22),
                new Vector3f(0.6f, 0.25f, 1.0f), new Vector3f(1, 0.004f, 0.0006f));
        shrineLight.setName("shrineLight");
        lights.add(shrineLight);
    }

    // ------------------------------------------------------------------------------------------------------------
    // zones

    private void addVillage() {
        // the lamp: post and glowing glass are two models with one origin (the glass alone is emissive, so only it
        // blooms). Its arm points at the fountain; its light follows it when you right-click drag it.
        float towardFountain = degrees(Math.atan2(PLAZA_X - LAMP_X, PLAZA_Z - LAMP_Z));
        lampPost = onGround(LAMP_NAME, "demo/lamp_post", ATLAS, LAMP_X, LAMP_Z, 0, towardFountain, 1, Look.SATIN);
        lampGlass = onGround(LAMP_GLASS_NAME, "demo/lamp_glass", ATLAS, LAMP_X, LAMP_Z, 0, towardFountain, 1, new Look(0.4f, 10, false, false, 1, 3f, null));
        lampLight.getPosition().set(lanternPosition());

        Entity fountain = onGround("fountain", "demo/fountain", ATLAS, PLAZA_X, PLAZA_Z, 0.3f, 0, 1, Look.SATIN);
        ParticleSystem spray = particles("spray", 90, 30, 1.45f, 3f, 1f);
        spray.setDirection(new Vector3f(0, 1, 0), 0.075f);
        emitters.add(new Emitter(spray, above(fountain, 2.5f), 0.6f, 0, 0.6f));

        // houses: the door is on the model's -z side, so rotY 0 faces north, 90 west, 180 south, 270 east
        building("cottage", "demo/house_cottage", PLAZA_X - 36, PLAZA_Z + 20, 270, 19, 13);
        building("townhouse", "demo/house_townhouse", PLAZA_X + 46, PLAZA_Z - 6, 90, 19, 13);
        Entity tavern = building("tavern", "demo/house_tavern", PLAZA_X - 2, PLAZA_Z + 52, 0, 25, 13);
        ParticleSystem chimneySmoke = particles("smoke", 5, 5, 4.5f, 8, -0.05f);
        chimneySmoke.setDirection(new Vector3f(0.35f, 1, 0.1f), 0.12f);
        emitters.add(new Emitter(chimneySmoke, new Vector3f(tavern.getPosition().x - 6, tavern.getPosition().y + 33, tavern.getPosition().z + 2.3f), 0.5f, 0, 0.5f));

        // market around the fountain. Everything stands clear of the mouths of the three paths (see PATHS): the
        // green stall beside the townhouse door, between the windmill path and the shrine path.
        prop("market stall red", "demo/stall_red", PLAZA_X + 22, PLAZA_Z - 20, 315);
        prop("market stall green", "demo/stall_green", PLAZA_X + 24, PLAZA_Z + 4, 0);
        prop("cart", "demo/cart", PLAZA_X - 20, PLAZA_Z + 4, 65);
        prop("bench", "demo/bench", PLAZA_X - 4, PLAZA_Z - 22, 180);
        prop("bench", "demo/bench", PLAZA_X + 4, PLAZA_Z + 24, 10);
        prop("barrel", "demo/barrel", PLAZA_X - 30, PLAZA_Z + 38, 0);
        prop("barrel", "demo/barrel", PLAZA_X - 24, PLAZA_Z + 36, 40);
        prop("crate", "demo/crate", PLAZA_X - 27, PLAZA_Z + 31, 20);
        prop("crate", "demo/crate", PLAZA_X + 31, PLAZA_Z - 26, 70);
        prop("signpost", "demo/signpost", PLAZA_X - 24, PLAZA_Z - 29, 200); // on the verge where the bridge path leaves the plaza

        // villagers (models face +z at rotY 0). A stall is a solid table 8 x 12 under its awning: the merchant
        // stands clear of its fountain end, calling across the plaza. The third walks from the tavern to the fountain.
        prop("villager merchant", "demo/villager_a", PLAZA_X + 15, PLAZA_Z - 13, 250);
        prop("villager", "demo/villager_b", PLAZA_X - 11, PLAZA_Z - 12, 50);
        prop("villager", "demo/villager_c", PLAZA_X - 9, PLAZA_Z + 27, 160);

        // little pumpkin patch behind the cottage
        prop("fence", "demo/fence", PLAZA_X - 36, PLAZA_Z + 62, 90);
        for (int i = 0; i < 5; i++)
            prop("pumpkin", "demo/pumpkin", PLAZA_X - 52 + i * 8 + random.nextFloat() * 3, PLAZA_Z + 50 + random.nextFloat() * 6, random.nextFloat() * 360);
    }

    private void addMills() {
        // watermill at the water's edge: its floor is level with the yard and its stone foundation stands in the
        // river. The wheel hangs on the river side and turns in the pool that heightAt digs for it.
        at("watermill", "demo/house_mill", ATLAS, MILL_X, BANK, MILL_Z, 270, 1, Look.MATTE);
        waterWheel = at("water wheel", "demo/water_wheel", ATLAS, WHEEL_X, WATER + 6.5f, MILL_Z, 90, 1, Look.MATTE);
        // the path to the bridge passes 9 units from the mill's east wall: the workbench stands against that wall
        // in the yard, the barrel round the corner by the north wall
        prop("workbench", "demo/workbench", MILL_X + 16.6f, MILL_Z + 12, 90);
        prop("barrel", "demo/barrel", MILL_X + 9, MILL_Z - 22, 0);
        // drops thrown up where the paddles come out of the water (the wheel's north side)
        ParticleSystem drops = particles("spray", 16, 7, 0.75f, 1.5f, 1f);
        drops.setDirection(new Vector3f(0, 1, -0.45f), 0.1f);
        emitters.add(new Emitter(drops, new Vector3f(WHEEL_X, WATER + 0.4f, MILL_Z - 9.5f), 3.5f, 0, 0.8f));

        // windmill on its knoll behind the village, rotor facing west toward the river
        Entity tower = building("windmill", "demo/windmill_tower", WINDMILL_X, WINDMILL_Z, 0, 13, 13);
        windmillBlades = at("windmill blades", "demo/windmill_blades", ATLAS, WINDMILL_X - 16.5f, tower.getPosition().y + 31, WINDMILL_Z, 270, 1, Look.MATTE);
        // hay bales beside the door, east of where the path from the plaza arrives
        prop("hay bale", "demo/hay_bale", WINDMILL_X + 9, WINDMILL_Z + 18, 20);
        prop("hay bale", "demo/hay_bale", WINDMILL_X + 15, WINDMILL_Z + 16, 75);
        prop("hay bale", "demo/hay_bale", WINDMILL_X + 12, WINDMILL_Z + 23, 140);
    }

    private void addShrine() {
        // centrepiece: a cluster of violet crystals on a stone plinth. Glossy, so the sun and the violet light put
        // highlights on its facets, and slightly emissive, so it glows a little and blooms.
        Entity plinth = prop("shrine plinth", "demo/shrine_plinth", SHRINE_X, SHRINE_Z, 0);
        Entity crystal = at("crystal", "demo/crystal", ATLAS, SHRINE_X, plinth.getPosition().y + 3.2f, SHRINE_Z, 315, 1, new Look(0.7f, 18, false, false, 1, 0.3f, null));

        // offering in front of it, on the side the path arrives from (north-west)
        prop("altar", "demo/shrine_altar", SHRINE_X - 20, SHRINE_Z - 20, 45);
        prop("treasure chest", "demo/chest", SHRINE_X - 27, SHRINE_Z - 12, 120);

        // ring of columns, open toward the path
        float[] columnAngles = {15, 75, 135, 195, 255, 315};
        for (int i = 0; i < columnAngles.length; i++) {
            double angle = Math.toRadians(columnAngles[i]);
            String model = i == 1 || i == 4 ? "demo/shrine_column_broken" : "demo/shrine_column";
            prop("shrine column", model, SHRINE_X + (float) Math.cos(angle) * 37, SHRINE_Z + (float) Math.sin(angle) * 37, columnAngles[i]);
        }
        prop("obelisk", "demo/shrine_obelisk", SHRINE_X + 44, SHRINE_Z + 10, 0);
        prop("obelisk", "demo/shrine_obelisk", SHRINE_X + 12, SHRINE_Z + 46, 30);

        // braziers flanking the approach: flames rise because their particle system has negative gravity
        for (float angle : new float[]{208, 242}) {
            double radians = Math.toRadians(angle);
            Entity brazier = prop("brazier", "demo/brazier", SHRINE_X + (float) Math.cos(radians) * 50, SHRINE_Z + (float) Math.sin(radians) * 50, angle);
            ParticleSystem flame = particles("flame", 26, 4, 0.9f, 5.5f, -0.3f);
            flame.setDirection(new Vector3f(0, 1, 0), 0.2f);
            emitters.add(new Emitter(flame, above(brazier, 19.5f), 0.8f, 0, 0.8f));
        }
        prop("knight", "demo/knight", SHRINE_X - 44, SHRINE_Z - 31, 315);

        // slow sparkles drifting around the crystal
        ParticleSystem sparkle = particles("sparkle", 9, 2.5f, 2.6f, 3.2f, -0.02f);
        emitters.add(new Emitter(sparkle, above(crystal, 13), 20, 12, 20));
    }

    private void addWaterside() {
        // arched bridge: the model's y = 0 is the deck at both ends, which rest on the levelled banks
        at("bridge", "demo/bridge", ATLAS, BRIDGE_X, BANK, BRIDGE_Z, 0, 1, Look.MATTE);

        // dock in the bay of the north lake (deck 3.4 above the model origin), reaching 4 units onto the shore,
        // with a moored rowboat and someone fishing from its end
        float deck = WATER + 1.6f, dockZ = shoreZ(DOCK_X, BAY_Z, 1, deck) - 14;
        at("dock", "demo/dock", ATLAS, DOCK_X, deck - 3.4f, dockZ, 0, 1, Look.MATTE);
        at("rowboat", "demo/rowboat", ATLAS, DOCK_X + 13, WATER - 1.1f, dockZ - 4, 20, 1, Look.MATTE);
        at("villager fishing", "demo/villager_d", ATLAS, DOCK_X, deck, dockZ - 14, 180, 1, Look.MATTE);
        prop("barrel", "demo/barrel", DOCK_X - 14, dockZ + 30, 0);
        prop("crate", "demo/crate", DOCK_X + 10, dockZ + 32, 35);

        // the ship: put on its route by sailShip() every frame. It must cast shadows to appear in the water's reflection.
        ship = at("ship", "demo/ship", ATLAS, ROUTE_X + ROUTE_RADIUS_X, WATER - SHIP_DRAUGHT, ROUTE_Z, 0, 1, Look.SATIN);
        measureRoute();
        ParticleSystem wake = particles("spray", 22, 0.5f, WAKE_SECONDS, 3.6f, 0);
        emitters.add(new Emitter(wake, wakeOrigin, 2.2f, 0, 2.2f));
        sailShip();

        // south pond: canoe pulled half onto the east shore, lily pads and reeds in the shallows
        float canoeZ = POND_Z - 56;
        at("canoe", "demo/canoe", ATLAS, shoreX(POND_X, canoeZ, 1, WATER) - 3, WATER - 0.6f, canoeZ, 290, 1, Look.MATTE);
        scatter(POND_X - 70, POND_X + 70, POND_Z - 90, POND_Z + 90, 12, 13, (x, z) -> {
            float depth = WATER - height(x, z);
            if (depth < 1.5f || depth > 7) return false;
            at(next("lily pad"), random.nextBoolean() ? "demo/lily_pad" : "demo/lily_pad_small", ATLAS, x, WATER + 0.15f, z, random.nextFloat() * 360, 1, Look.FOLIAGE);
            return true;
        });

        // rocks along the shores of the pond, the river and the lake, standing in the shallows or on the water's edge
        String[] rocks = {"demo/rock_large", "demo/rock_wide", "demo/rock_small", "demo/rocks_tall"};
        scatter(1090, 1400, 1040, 1470, 13, 34, (x, z) -> {
            float depth = WATER - height(x, z);
            if (depth < -1.2f || depth > 2.8f || nearSomething(x, z, 14) || keptOpen(x, z)) return false;
            float scale = 0.4f + random.nextFloat() * 0.35f;
            onGround(next("shore rock"), rocks[random.nextInt(rocks.length)], ATLAS, x, z, -2.5f * scale, random.nextFloat() * 360, scale, Look.MATTE);
            return true;
        });

        // fireflies over the pond's forest-side shore: no gravity, random drift
        ParticleSystem fireflies = particles("firefly", 7, 3.5f, 5, 2.2f, 0);
        emitters.add(new Emitter(fireflies, new Vector3f(POND_X - 52, WATER + 9, POND_Z - 40), 40, 6, 55));
    }

    private void addCamp() {
        // forest camp at the end of the path over the bridge, on its own patch of level ground. The path arrives
        // from the north-east and stays open: tent, log stack and stump stand round the far side of the fire.
        Entity campfire = prop("campfire", "demo/campfire", CAMP_X, CAMP_Z, 0);
        prop("tent", "demo/tent", CAMP_X - 15, CAMP_Z - 9, 120);
        prop("log stack", "demo/log_stack", CAMP_X - 20, CAMP_Z + 9, 25);
        prop("stump", "demo/stump", CAMP_X - 8, CAMP_Z + 21, 0);
        prop("villager camper", "demo/villager_c", CAMP_X - 8, CAMP_Z + 7, 130);

        ParticleSystem flame = particles("flame", 30, 4.5f, 0.95f, 6, -0.3f);
        flame.setDirection(new Vector3f(0, 1, 0), 0.22f);
        emitters.add(new Emitter(flame, above(campfire, 2), 1.2f, 0, 1.2f));
        ParticleSystem smoke = particles("smoke", 4, 5, 4, 7, -0.05f);
        smoke.setDirection(new Vector3f(0.3f, 1, 0.1f), 0.15f);
        emitters.add(new Emitter(smoke, above(campfire, 8), 0.8f, 0, 0.8f));
    }

    private static final String[] CONIFERS = {"demo/pine", "demo/pine_crooked", "demo/tree_round", "demo/tree_tall", "demo/tree_crooked", "demo/tree_bushy"};
    private static final String[] BROADLEAF = {"demo/tree_oak", "demo/tree_oak", "demo/tree_oak", "demo/tree_autumn"};

    /** Trees on the valley floor. Each is its own entity (shared TexturedModels: every tree type is one batch), so they can be selected and moved. */
    private void addForests() {
        // the forest on the west bank (mostly conifers), groves east of the village and behind the shrine hill
        scatter(1015, 1205, 1075, 1335, 44, 19, (x, z) -> tree(x, z, random.nextFloat() < 0.75f ? CONIFERS : BROADLEAF));
        scatter(1405, 1500, 1150, 1335, 13, 22, (x, z) -> tree(x, z, random.nextFloat() < 0.4f ? CONIFERS : BROADLEAF));
        scatter(1385, 1480, 1040, 1125, 8, 22, (x, z) -> tree(x, z, random.nextFloat() < 0.5f ? CONIFERS : BROADLEAF));
        scatter(1455, 1540, 1340, 1470, 8, 22, (x, z) -> tree(x, z, CONIFERS));
        // a few around the village as framing: none against the plaza or in front of the mills
        float[][] villageTrees = {{1398, 1272}, {1404, 1204}, {1354, 1322}, {1270, 1300}, {1330, 1088}};
        for (float[] p : villageTrees) tree(p[0], p[1], BROADLEAF);

        // undergrowth in the west forest
        scatter(1015, 1200, 1080, 1330, 10, 14, (x, z) -> plant(x, z, random.nextBoolean() ? "demo/bush" : "demo/bush_detailed", "bush"));
        scatter(1015, 1200, 1080, 1330, 6, 14, (x, z) -> plant(x, z, "demo/mushrooms", "mushrooms"));
        scatter(1015, 1200, 1080, 1330, 4, 14, (x, z) -> {
            if (!dryLand(x, z) || keepClear(x, z)) return false;
            onGround(next("stump"), "demo/stump", ATLAS, x, z, -0.4f, random.nextFloat() * 360, 0.9f + random.nextFloat() * 0.4f, Look.MATTE);
            return true;
        });
        scatter(1015, 1200, 1080, 1330, 5, 16, (x, z) -> {
            if (!dryLand(x, z) || keepClear(x, z)) return false;
            float scale = 0.3f + random.nextFloat() * 0.25f;
            onGround(next("forest rock"), random.nextBoolean() ? "demo/rock_wide" : "demo/rock_small", ATLAS, x, z, -2 * scale, random.nextFloat() * 360, scale, Look.MATTE);
            return true;
        });
    }

    /**
     * The woods on the hills round the valley: far too many trees to be entities of their own (each entity is a
     * draw call in every pass), so they are baked into one mesh per eighth of the ring. Rocks stand where the
     * ground is bare rock.
     */
    private void addHillForests() {
        final int sectors = 8;
        Batch[] batches = new Batch[sectors];
        for (int i = 0; i < sectors; i++) batches[i] = new Batch();
        String[] hillTrees = {"demo/tree_tall", "demo/tree_round", "demo/tree_bushy", "demo/tree_crooked", "demo/tree_tall", "demo/pine", "demo/tree_oak", "demo/tree_autumn"};
        String[] hillRocks = {"demo/rock_large", "demo/rock_wide", "demo/rocks_tall"};

        final float spacing = 27;
        for (float gridX = VALLEY_X - 760; gridX < VALLEY_X + 760; gridX += spacing)
            for (float gridZ = VALLEY_Z - 760; gridZ < VALLEY_Z + 760; gridZ += spacing) {
                float x = gridX + (random.nextFloat() - 0.5f) * 20, z = gridZ + (random.nextFloat() - 0.5f) * 20;
                float fromCentre = distance(x, z, VALLEY_X, VALLEY_Z);
                if (x < 10 || z < 10 || x > Terrain.SIZE * 3 - 10 || z > Terrain.SIZE * 3 - 10 || fromCentre > 760) continue;
                if (fromCentre > 600 && random.nextFloat() < 0.45f) continue; // thinner where the haze hides most of it
                if (woodland(x, z) < 0.5f || keepClear(x, z)) continue;
                GroundFit ground = fitGround(x, z, 8);
                if (ground.height() < WATER + 2.5f || tooClose(x, z, 15)) continue; // not in the water, not on top of a hand-placed tree
                Batch batch = batches[(int) ((Math.atan2(z - VALLEY_Z, x - VALLEY_X) + Math.PI) / (2 * Math.PI) * sectors) % sectors];
                if (rockiness(x, z, ground.height(), (float) Math.hypot(ground.slopeX(), ground.slopeZ())) > 0.5f) {
                    if (random.nextFloat() < 0.3f) {
                        float scale = 0.8f + random.nextFloat() * 1.2f;
                        batch.add(hillRocks[random.nextInt(hillRocks.length)], x, ground.height() - 3 * scale, z, random.nextFloat() * 360, scale);
                    }
                } else if (random.nextFloat() < 0.9f) {
                    batch.add(hillTrees[random.nextInt(hillTrees.length)], x, ground.height() - ground.unevenness() - 0.5f, z, random.nextFloat() * 360, 1.15f + random.nextFloat() * 0.6f);
                }
            }
        for (Batch batch : batches) batch.build("hill forest", ATLAS, Look.MATTE);
    }

    /**
     * Small plants. The cut-out cards show three ModelTexture features at once: a 2x2 texture atlas
     * (one mesh, the entity's texture index picks the plant), transparency and fake lighting.
     */
    private void addGroundCover() {
        final int grass = 0, flowers = 1, reeds = 2, leafy = 3;

        // meadow around the village and the paths
        scatter(1270, 1420, 1100, 1330, 18, 9, (x, z) -> card(x, z, random.nextFloat() < 0.6f ? grass : flowers, "meadow grass"));
        scatter(1290, 1330, 1330, 1380, 4, 9, (x, z) -> card(x, z, random.nextBoolean() ? grass : flowers, "meadow grass"));
        // forest floor
        scatter(1020, 1200, 1085, 1325, 10, 9, (x, z) -> card(x, z, leafy, "fern"));
        // reeds standing in the shallows of the south pond, the river and the north lake
        scatter(1110, 1290, 1040, 1470, 18, 8, (x, z) -> {
            float depth = WATER - height(x, z);
            if (depth < 0.3f || depth > 3.2f || keepClear(x, z) || keptOpen(x, z)) return false;
            foliageCard(next("reeds"), x, z, reeds, 1.1f + random.nextFloat() * 0.5f);
            return true;
        });

        // solid low-poly flowers near the plaza
        String[] flowerModels = {"demo/flower_red", "demo/flower_yellow", "demo/flower_purple"};
        scatter(1285, 1360, 1205, 1275, 9, 7, (x, z) -> plant(x, z, flowerModels[random.nextInt(flowerModels.length)], "flower"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // the ship

    /** Tabulates how far along the route each degree of the ellipse is, so the ship can sail it at a constant speed. */
    private void measureRoute() {
        float x = ROUTE_X + ROUTE_RADIUS_X, z = ROUTE_Z;
        for (int degree = 1; degree <= 360; degree++) {
            float nextX = ROUTE_X + ROUTE_RADIUS_X * (float) Math.cos(Math.toRadians(degree)), nextZ = ROUTE_Z + ROUTE_RADIUS_Z * (float) Math.sin(Math.toRadians(degree));
            routeDistance[degree] = routeDistance[degree - 1] + distance(x, z, nextX, nextZ);
            x = nextX;
            z = nextZ;
        }
    }

    /**
     * Puts the ship where it is now: by the world clock, or by the time within a b-roll shot that films it.
     * Heading follows the direction of travel (the bow is the model's +z end); a slow bob and a few degrees of
     * roll keep it alive. The engine applies rotZ before rotY, so rotZ is a roll around the hull's own long axis.
     */
    private void sailShip() {
        Float shotStart = shot == null ? null : SHIP_SHOTS.get(shot);
        float time = shotStart != null ? shotStart + shotSeconds : DisplayManager.getTime();
        float lap = routeDistance[360], sailed = ((time * SHIP_SPEED) % lap + lap) % lap;

        int degree = 0;
        while (degree < 359 && routeDistance[degree + 1] < sailed) degree++;
        double angle = Math.toRadians(degree + (sailed - routeDistance[degree]) / (routeDistance[degree + 1] - routeDistance[degree]));
        float x = ROUTE_X + ROUTE_RADIUS_X * (float) Math.cos(angle), z = ROUTE_Z + ROUTE_RADIUS_Z * (float) Math.sin(angle);
        float headingX = -ROUTE_RADIUS_X * (float) Math.sin(angle), headingZ = ROUTE_RADIUS_Z * (float) Math.cos(angle), length = (float) Math.hypot(headingX, headingZ);

        ship.getPosition().set(x, WATER - SHIP_DRAUGHT + 0.25f * (float) Math.sin(time * 1.3f), z);
        ship.setRotY(degrees(Math.atan2(headingX, headingZ)));
        ship.setRotZ(degrees(Math.toRadians(2.2f * Math.sin(time * 0.9f))));
        wakeOrigin.set(x - headingX / length * SHIP_STERN, WATER + 0.35f, z - headingZ / length * SHIP_STERN); // foam is left behind the stern
    }

    // ------------------------------------------------------------------------------------------------------------
    // placement helpers

    private boolean tree(float x, float z, String[] models) {
        if (!dryLand(x, z) || keepClear(x, z)) return false;
        onGround(next("tree"), models[random.nextInt(models.length)], ATLAS, x, z, -0.8f, random.nextFloat() * 360, 0.95f + random.nextFloat() * 0.5f, Look.MATTE);
        return true;
    }

    private boolean plant(float x, float z, String model, String name) {
        if (!dryLand(x, z) || keepClear(x, z)) return false;
        onGround(next(name), model, ATLAS, x, z, -0.2f, random.nextFloat() * 360, 0.9f + random.nextFloat() * 0.4f, Look.FOLIAGE);
        return true;
    }

    private boolean card(float x, float z, int atlasIndex, String name) {
        if (!dryLand(x, z) || keepClear(x, z)) return false;
        foliageCard(next(name), x, z, atlasIndex, 0.7f + random.nextFloat() * 0.5f);
        return true;
    }

    private void foliageCard(String name, float x, float z, int atlasIndex, float scale) {
        Entity card = onGround(name, "demo/foliage_card", "demo/foliage_atlas", x, z, -0.4f, random.nextFloat() * 360, scale, Look.CUTOUT_ATLAS);
        card.setTextureIndex(atlasIndex);
    }

    private boolean dryLand(float x, float z) {
        return height(x, z) > WATER + 2.5f;
    }

    /** Keeps scattered plants off the paths, the trodden patches and the building plots. */
    private boolean keepClear(float x, float z) {
        return pathDistance(x, z) < 5 || nearSomething(x, z, 0);
    }

    /** Stretches of shore that stay free of rocks and reeds: under the bridge, and the river mouth, where the b-roll camera watches the ship pass. */
    private static boolean keptOpen(float x, float z) {
        return Math.abs(x - BRIDGE_X) < 48 && Math.abs(z - BRIDGE_Z) < 26 || Math.abs(x - BRIDGE_X) < 50 && z < 1100;
    }

    private boolean nearSomething(float x, float z, float margin) {
        for (float[] plot : PLOTS)
            if (distance(x, z, plot[0], plot[1]) < plot[2] + margin) return true;
        return false;
    }

    /** Walks from (x, fromZ) in steps along z until the ground reaches the given height: where a shore is. */
    private float shoreZ(float x, float fromZ, float step, float level) {
        float z = fromZ;
        for (int i = 0; i < 200 && height(x, z) < level; i++) z += step;
        return z;
    }

    private float shoreX(float fromX, float z, float step, float level) {
        float x = fromX;
        for (int i = 0; i < 200 && height(x, z) < level; i++) x += step;
        return x;
    }

    /** Particle system using one of the 4x4 atlases in res/particles/demo. gravity: 1 falls, 0 floats, negative rises. */
    private ParticleSystem particles(String atlas, float perSecond, float speed, float life, float scale, float gravity) {
        // fire and sparks are brighter than white, so they bloom
        float glow = switch (atlas) {
            case "flame" -> 2.6f;
            case "sparkle", "firefly" -> 2.2f;
            default -> 1;
        };
        return particles("particles/demo/" + atlas + ".png", 4, glow, perSecond, speed, life, scale, gravity);
    }

    // ------------------------------------------------------------------------------------------------------------
    // entity creation

    /** Kit prop standing on the terrain. */
    private Entity prop(String name, String model, float x, float z, float rotY) {
        return onGround(name, model, ATLAS, x, z, 0, rotY, 1, Look.MATTE);
    }

    /**
     * A house: floor at the average ground height under its footprint. The models carry a buried foundation
     * storey, so a downhill side shows stonework instead of a gap.
     */
    private Entity building(String name, String model, float x, float z, float rotY, float halfLength, float halfDepth) {
        boolean turned = Math.round(rotY / 90) % 2 != 0;
        float halfX = turned ? halfDepth : halfLength, halfZ = turned ? halfLength : halfDepth, sum = 0;
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j++)
                sum += height(x + i * halfX, z + j * halfZ);
        return at(name, model, ATLAS, x, sum / 9, z, rotY, 1, Look.MATTE);
    }

    // ------------------------------------------------------------------------------------------------------------
    // maths

    private static float distance(float x, float z, float toX, float toZ) {
        return (float) Math.hypot(x - toX, z - toZ);
    }

    private static float segmentDistance(float x, float z, float ax, float az, float bx, float bz) {
        float dx = bx - ax, dz = bz - az;
        float t = Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz)));
        return (float) Math.hypot(x - (ax + t * dx), z - (az + t * dz));
    }

    private static float mix(float a, float b, float amount) {
        return a + (b - a) * amount;
    }

    /** 0 at edge0, 1 at edge1, smooth in between; the edges may be given in either order. */
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
}
